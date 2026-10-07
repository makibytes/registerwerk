package de.makibytes.registerwerk.kyc.web.dto;

import de.makibytes.registerwerk.kyc.internal.KycReviewService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The scoped, read-only entity view the compliance officer decides on (T8-03). An explicit allow-list of
 * fields: the entity's contact data, identity-provider settings, wallets, balances, relationship manager and
 * client classification are deliberately not part of it, and a new column on the entity never appears here
 * unless it is added to this record on purpose.
 */
public record KycReviewResponse(
        UUID entityId,
        String entityNumber,
        String legalName,
        String entityType,
        String entityStatus,
        String registrationCountry,
        String registrationNumber,
        String leiCode,
        LocalDate incorporationDate,
        String homeJurisdiction,
        String kycStatus,
        LocalDate kycExpiryDate,
        List<KycDocumentResponse> documents,
        KycComplianceResponse checklist,
        List<KycJurisdictionApprovalResponse> jurisdictionApprovals,
        OwnershipSummaryResponse ownership,
        List<OwnerReview> beneficialOwners,
        Screening screening,
        List<String> gaps,
        List<KycDecisionResponse> decisions
) {
    /** A beneficial owner with the screening and EDD state the decision needs. */
    public record OwnerReview(BeneficialOwnerResponse owner, boolean screeningUnresolved, boolean eddInForce,
                              Instant eddReviewDue) {}

    public record Screening(boolean entityHitUnresolved, boolean beneficialOwnerHitUnresolved,
                            boolean relyingOnStaleResult) {}

    public static KycReviewResponse from(KycReviewService.Review r) {
        var e = r.entity();
        return new KycReviewResponse(
                e.getId(), e.getEntityNumber(), e.getCurrentName(),
                e.getType() != null ? e.getType().name() : null,
                e.getStatus() != null ? e.getStatus().name() : null,
                e.getRegistrationCountry(), e.getRegistrationNumber(), e.getLeiCode(), e.getIncorporationDate(),
                r.homeJurisdiction().name(), e.getKycStatus().name(), e.getKycExpiryDate(),
                r.documents().stream().map(KycDocumentResponse::from).toList(),
                KycComplianceResponse.from(r.checklist()),
                r.jurisdictionApprovals().stream().map(KycJurisdictionApprovalResponse::from).toList(),
                OwnershipSummaryResponse.from(r.ownership()),
                r.owners().stream().map(o -> new OwnerReview(
                        BeneficialOwnerResponse.from(o.owner(), o.person()), o.screeningUnresolved(),
                        o.eddReviewDue().isPresent(), o.eddReviewDue().orElse(null))).toList(),
                new Screening(r.screening().entityHitUnresolved(), r.screening().beneficialOwnerHitUnresolved(),
                        r.screening().relyingOnStaleResult()),
                r.gaps(),
                r.decisions().stream().map(KycDecisionResponse::from).toList());
    }
}
