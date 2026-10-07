package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.internal.KycReviewService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One entity in the KYC work queue and why it is there (reason codes, machine readable). */
public record KycQueueItemResponse(
        UUID entityId,
        String entityName,
        String homeJurisdiction,
        String kycStatus,
        LocalDate kycExpiryDate,
        List<String> reasons
) {
    public static KycQueueItemResponse from(KycReviewService.QueueItem i) {
        return new KycQueueItemResponse(i.entityId(), i.entityName(), i.homeJurisdiction().name(),
                i.kycStatus().name(), i.kycExpiryDate(), i.reasons());
    }
}
