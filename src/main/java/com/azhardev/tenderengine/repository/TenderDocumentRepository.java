package com.azhardev.tenderengine.repository;

import com.azhardev.tenderengine.entity.TenderDocumentRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenderDocumentRepository extends JpaRepository<TenderDocumentRecord, UUID> {

    Optional<TenderDocumentRecord> findByDocumentHash(String documentHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TenderDocumentRecord t WHERE t.documentHash = :documentHash")
    Optional<TenderDocumentRecord> findAndLockByDocumentHash(@Param("documentHash") String documentHash);

    @Query(value = "SELECT pg_try_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
    boolean tryAcquireAdvisoryLock(@Param("lockKey") String lockKey);

    @Query(value = "SELECT * FROM tender_document_records t WHERE t.status = 'COMPLETED' AND (:authority IS NULL OR t.extracted_metadata->>'issuingAuthority' ILIKE CONCAT('%', :authority, '%'))", nativeQuery = true)
    List<TenderDocumentRecord> searchByIssuingAuthority(@Param("authority") String authority);
}
