package com.azhardev.tenderengine.entity;

import com.azhardev.tenderengine.dto.TenderMetadataSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "tender_document_records",
    indexes = {
        @Index(name = "idx_tender_doc_hash", columnList = "document_hash", unique = true),
        @Index(name = "idx_tender_doc_status", columnList = "status")
    }
)
public class TenderDocumentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "document_hash", nullable = false, unique = true, length = 64)
    private String documentHash;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ProcessingStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_metadata", columnDefinition = "jsonb")
    private TenderMetadataSchema extractedMetadata;

    @Column(name = "processing_duration_ms")
    private Long processingDurationMs;

    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TenderDocumentRecord() {
    }

    public TenderDocumentRecord(UUID id, String documentHash, String fileName, Integer pageCount,
                                ProcessingStatus status, TenderMetadataSchema extractedMetadata,
                                Long processingDurationMs, Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.documentHash = documentHash;
        this.fileName = fileName;
        this.pageCount = pageCount;
        this.status = status;
        this.extractedMetadata = extractedMetadata;
        this.processingDurationMs = processingDurationMs;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static TenderDocumentRecordBuilder builder() {
        return new TenderDocumentRecordBuilder();
    }

    public static class TenderDocumentRecordBuilder {
        private UUID id;
        private String documentHash;
        private String fileName;
        private Integer pageCount;
        private ProcessingStatus status;
        private TenderMetadataSchema extractedMetadata;
        private Long processingDurationMs;
        private Long version;
        private Instant createdAt;
        private Instant updatedAt;

        TenderDocumentRecordBuilder() {
        }

        public TenderDocumentRecordBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public TenderDocumentRecordBuilder documentHash(String documentHash) {
            this.documentHash = documentHash;
            return this;
        }

        public TenderDocumentRecordBuilder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public TenderDocumentRecordBuilder pageCount(Integer pageCount) {
            this.pageCount = pageCount;
            return this;
        }

        public TenderDocumentRecordBuilder status(ProcessingStatus status) {
            this.status = status;
            return this;
        }

        public TenderDocumentRecordBuilder extractedMetadata(TenderMetadataSchema extractedMetadata) {
            this.extractedMetadata = extractedMetadata;
            return this;
        }

        public TenderDocumentRecordBuilder processingDurationMs(Long processingDurationMs) {
            this.processingDurationMs = processingDurationMs;
            return this;
        }

        public TenderDocumentRecordBuilder version(Long version) {
            this.version = version;
            return this;
        }

        public TenderDocumentRecordBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public TenderDocumentRecordBuilder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public TenderDocumentRecord build() {
            return new TenderDocumentRecord(id, documentHash, fileName, pageCount, status,
                    extractedMetadata, processingDurationMs, version, createdAt, updatedAt);
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public void setDocumentHash(String documentHash) {
        this.documentHash = documentHash;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public ProcessingStatus getStatus() {
        return status;
    }

    public void setStatus(ProcessingStatus status) {
        this.status = status;
    }

    public TenderMetadataSchema getExtractedMetadata() {
        return extractedMetadata;
    }

    public void setExtractedMetadata(TenderMetadataSchema extractedMetadata) {
        this.extractedMetadata = extractedMetadata;
    }

    public Long getProcessingDurationMs() {
        return processingDurationMs;
    }

    public void setProcessingDurationMs(Long processingDurationMs) {
        this.processingDurationMs = processingDurationMs;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
