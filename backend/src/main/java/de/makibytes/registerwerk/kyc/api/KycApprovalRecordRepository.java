package de.makibytes.registerwerk.kyc.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KycApprovalRecordRepository extends JpaRepository<KycApprovalRecord, UUID> {
}
