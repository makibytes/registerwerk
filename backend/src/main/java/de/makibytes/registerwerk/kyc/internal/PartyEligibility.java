package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The single list of tests a counterparty must pass (Phase 5, 5C-03). Both
 * {@link OutboundDestinationGateImpl} (outbound token destinations) and
 * {@link PartyEligibilityGateImpl} (trading / repo parties) call this, so the two gates can never
 * drift apart again: entity ACTIVE, KYC APPROVED and not past its expiry date, no unresolved
 * entity or beneficial-owner screening hit, no active §16 eWpG Sperrvermerk on entity or wallet.
 *
 * <p>Each reason is a predicate that completes "the entity ..." so callers can embed it in their
 * own message.
 */
final class PartyEligibility {

    private PartyEligibility() {
    }

    static List<String> reasons(LegalEntity entity, String normalizedWallet, ScreeningGate screeningGate,
                                HolderBlockGate holderBlockGate, LocalDate today) {
        List<String> reasons = new ArrayList<>();
        addIfPresent(reasons, statusReason(entity));
        if (entity.getKycStatus() != KycStatus.APPROVED) {
            reasons.add("has KYC status " + entity.getKycStatus() + " (approval required)");
        } else if (entity.getKycExpiryDate() != null && entity.getKycExpiryDate().isBefore(today)) {
            reasons.add("has an expired KYC (expired " + entity.getKycExpiryDate() + ")");
        }
        addIfPresent(reasons, screeningReason(entity, screeningGate));
        // Entity-only callers (repo desk, lending) must also see wallet-only blocks on the entity's holder wallets (6-25).
        if (holderBlockGate.isBlocked(entity.getId(), normalizedWallet)
                || (normalizedWallet == null && holderBlockGate.isEntityBlocked(entity.getId()))) {
            reasons.add("(or its wallet) is subject to an active §16 eWpG Sperrvermerk (legal block)");
        }
        return reasons;
    }

    /**
     * The HARD subset of {@link #reasons}: entity status other than ACTIVE and an unresolved
     * sanctions-screening result on the entity or a beneficial owner (9A-08, review phase 9). Everything else
     * (KYC expiry / not approved, Sperrvermerk) is SOFT. Callers that protect an EXISTING exposure (a repo
     * creditor's margin call / default notice) refuse only on these; both lists use the same predicates
     * ({@link #statusReason}, {@link #screeningReason}) so they cannot drift.
     */
    static List<String> hardStops(LegalEntity entity, ScreeningGate screeningGate) {
        List<String> reasons = new ArrayList<>();
        addIfPresent(reasons, statusReason(entity));
        addIfPresent(reasons, screeningReason(entity, screeningGate));
        return reasons;
    }

    private static String statusReason(LegalEntity entity) {
        return entity.getStatus() != EntityStatus.ACTIVE ? "is in status " + entity.getStatus() : null;
    }

    private static String screeningReason(LegalEntity entity, ScreeningGate screeningGate) {
        return screeningGate.hasUnresolvedHit(entity.getId()) || screeningGate.hasUnresolvedBeneficialOwnerHit(entity.getId())
                ? "has an unresolved sanctions-screening result" : null;
    }

    private static void addIfPresent(List<String> reasons, String reason) {
        if (reason != null) {
            reasons.add(reason);
        }
    }
}
