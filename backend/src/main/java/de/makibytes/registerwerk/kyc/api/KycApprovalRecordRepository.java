package de.makibytes.registerwerk.kyc.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycApprovalRecordRepository extends JpaRepository<KycApprovalRecord, UUID> {

    /** Entity-level approval decisions, newest first (compliance review history). */
    List<KycApprovalRecord> findByEntityIdOrderByCreatedAtDesc(UUID entityId);
}
