package com.azhardev.tenderengine.service;

import com.azhardev.tenderengine.dto.TenderMetadataSchema;
import com.azhardev.tenderengine.entity.ProcessingStatus;
import com.azhardev.tenderengine.entity.TenderDocumentRecord;
import com.azhardev.tenderengine.exception.ConcurrentProcessingException;
import com.azhardev.tenderengine.exception.DocumentProcessingException;
import com.azhardev.tenderengine.repository.TenderDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class DatabaseCacheLockService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCacheLockService.class);

    private final TenderDocumentRepository repository;

    @Value("${app.lock.stale-timeout-seconds:120}")
    private long staleTimeoutSeconds;

    public DatabaseCacheLockService(TenderDocumentRepository repository) {
        this.repository = repository;
    }

    /**
     * Sub-second fast cache lookup to immediately return COMPLETED records.
     */
    @Transactional(readOnly = true)
    public Optional<TenderDocumentRecord> checkFastCachePath(String documentHash) {
        log.debug("Checking fast cache path for document hash: {}", documentHash);
        return repository.findByDocumentHash(documentHash)
                .filter(record -> record.getStatus() == ProcessingStatus.COMPLETED);
    }

    /**
     * Looks up any document record by its SHA-256 hash.
     */
    @Transactional(readOnly = true)
    public Optional<TenderDocumentRecord> findByDocumentHash(String documentHash) {
        log.debug("Looking up document record for document hash: {}", documentHash);
        return repository.findByDocumentHash(documentHash);
    }

    /**
     * Attempts to acquire an advisory lock and a row-level pessimistic write lock.
     * Throws ConcurrentProcessingException if another worker/thread is currently processing the same document.
     * If an existing lock is older than staleTimeoutSeconds, recovers the lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TenderDocumentRecord acquireLockOrThrow(String documentHash, String fileName, int pageCount) {
        log.debug("Attempting to acquire transactional lock for document hash: {}", documentHash);

        boolean advisoryLockAcquired;
        try {
            advisoryLockAcquired = repository.tryAcquireAdvisoryLock(documentHash);
        } catch (Exception ex) {
            log.debug("Advisory lock check skipped/unsupported in current database dialect: {}", ex.getMessage());
            advisoryLockAcquired = true;
        }

        if (!advisoryLockAcquired) {
            log.warn("Advisory lock acquisition failed for document hash: {}. Another worker holds this lock.", documentHash);
            throw new ConcurrentProcessingException(
                    "Advisory lock acquisition failed for document hash: " + documentHash +
                    ". Another worker is actively processing this document."
            );
        }

        Optional<TenderDocumentRecord> existingRecordOpt = repository.findAndLockByDocumentHash(documentHash);
        if (existingRecordOpt.isPresent()) {
            TenderDocumentRecord existing = existingRecordOpt.get();

            if (existing.getStatus() == ProcessingStatus.COMPLETED) {
                log.info("Document with hash: {} is already COMPLETED. Returning cached record.", documentHash);
                return existing;
            }

            if (existing.getStatus() == ProcessingStatus.LOCKED_PROCESSING) {
                Instant referenceTime = existing.getUpdatedAt() != null ? existing.getUpdatedAt() : existing.getCreatedAt();
                long elapsedSeconds = referenceTime != null
                        ? Duration.between(referenceTime, Instant.now()).getSeconds()
                        : Long.MAX_VALUE;

                if (elapsedSeconds >= staleTimeoutSeconds) {
                    log.warn("Stale lock detected for document hash: {}. Lock held since {} (elapsed: {}s > timeout: {}s). Recovering stale lock.",
                            documentHash, referenceTime, elapsedSeconds, staleTimeoutSeconds);
                    existing.setFileName(fileName);
                    existing.setPageCount(pageCount);
                    existing.setExtractedMetadata(null);
                    existing.setProcessingDurationMs(null);
                    existing.setUpdatedAt(Instant.now());
                    return repository.saveAndFlush(existing);
                }

                log.warn("Document with hash: {} is already in LOCKED_PROCESSING state (elapsed: {}s < timeout: {}s).",
                        documentHash, elapsedSeconds, staleTimeoutSeconds);
                throw new ConcurrentProcessingException(
                        "Document is actively being processed by another worker. Hash: " + documentHash
                );
            }

            if (existing.getStatus() == ProcessingStatus.FAILED) {
                log.info("Document with hash: {} previously FAILED. Resetting to LOCKED_PROCESSING for retry.", documentHash);
                existing.setStatus(ProcessingStatus.LOCKED_PROCESSING);
                existing.setFileName(fileName);
                existing.setPageCount(pageCount);
                existing.setExtractedMetadata(null);
                existing.setProcessingDurationMs(null);
                existing.setUpdatedAt(Instant.now());
                return repository.saveAndFlush(existing);
            }
        }

        log.info("Inserting new LOCKED_PROCESSING record for hash: {}", documentHash);
        TenderDocumentRecord newRecord = TenderDocumentRecord.builder()
                .documentHash(documentHash)
                .fileName(fileName)
                .pageCount(pageCount)
                .status(ProcessingStatus.LOCKED_PROCESSING)
                .build();

        try {
            return repository.saveAndFlush(newRecord);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent insert race detected for document hash: {}", documentHash, ex);
            throw new ConcurrentProcessingException(
                    "Concurrent insert race detected for document hash: " + documentHash, ex
            );
        }
    }

    /**
     * Marks the record as COMPLETED with extracted JSONB metadata.
     */
    @Transactional
    public TenderDocumentRecord completeRecord(UUID recordId, TenderMetadataSchema metadata, long processingDurationMs) {
        log.info("Completing record id: {} with duration: {}ms", recordId, processingDurationMs);
        TenderDocumentRecord record = repository.findById(recordId)
                .orElseThrow(() -> new DocumentProcessingException("Document record not found for id: " + recordId));

        record.setExtractedMetadata(metadata);
        record.setStatus(ProcessingStatus.COMPLETED);
        record.setProcessingDurationMs(processingDurationMs);
        return repository.saveAndFlush(record);
    }

    /**
     * Overloaded method to complete record by document hash.
     */
    @Transactional
    public TenderDocumentRecord completeRecord(String documentHash, TenderMetadataSchema metadata, long processingDurationMs) {
        log.info("Completing record hash: {} with duration: {}ms", documentHash, processingDurationMs);
        TenderDocumentRecord record = repository.findByDocumentHash(documentHash)
                .orElseThrow(() -> new DocumentProcessingException("Document record not found for hash: " + documentHash));

        record.setExtractedMetadata(metadata);
        record.setStatus(ProcessingStatus.COMPLETED);
        record.setProcessingDurationMs(processingDurationMs);
        return repository.saveAndFlush(record);
    }

    /**
     * Marks the record as FAILED.
     */
    @Transactional
    public TenderDocumentRecord failRecord(UUID recordId, long processingDurationMs) {
        log.warn("Marking record id: {} as FAILED after {}ms", recordId, processingDurationMs);
        TenderDocumentRecord record = repository.findById(recordId)
                .orElseThrow(() -> new DocumentProcessingException("Document record not found for id: " + recordId));

        record.setStatus(ProcessingStatus.FAILED);
        record.setProcessingDurationMs(processingDurationMs);
        return repository.saveAndFlush(record);
    }

    /**
     * Overloaded method to mark record as FAILED by document hash.
     */
    @Transactional
    public TenderDocumentRecord failRecord(String documentHash, long processingDurationMs) {
        log.warn("Marking record hash: {} as FAILED after {}ms", documentHash, processingDurationMs);
        TenderDocumentRecord record = repository.findByDocumentHash(documentHash)
                .orElseThrow(() -> new DocumentProcessingException("Document record not found for hash: " + documentHash));

        record.setStatus(ProcessingStatus.FAILED);
        record.setProcessingDurationMs(processingDurationMs);
        return repository.saveAndFlush(record);
    }
}
