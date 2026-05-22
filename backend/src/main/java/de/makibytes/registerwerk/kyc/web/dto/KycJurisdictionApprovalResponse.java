package de.makibytes.registerwerk.kyc.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response DTO for a per-jurisdiction KYC approval record.
 */
public record KycJurisdictionApprovalResponse(
    UUID id,
    UUID entityId,
    String jurisdiction,
    String jurisdictionDisplayName,
    String status,
    UUID approvedBy,
    Instant approvedAt,
    LocalDate expiresAt,
    String rejectionReason,
    String overrideNote
) {}
