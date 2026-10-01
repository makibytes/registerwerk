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
        if (entity.getStatus() != EntityStatus.ACTIVE) {
            reasons.add("is in status " + entity.getStatus());
        }
        if (entity.getKycStatus() != KycStatus.APPROVED) {
            reasons.add("has KYC status " + entity.getKycStatus() + " (approval required)");
        } else if (entity.getKycExpiryDate() != null && entity.getKycExpiryDate().isBefore(today)) {
            reasons.add("has an expired KYC (expired " + entity.getKycExpiryDate() + ")");
        }
        if (screeningGate.hasUnresolvedHit(entity.getId()) || screeningGate.hasUnresolvedBeneficialOwnerHit(entity.getId())) {
            reasons.add("has an unresolved sanctions-screening result");
        }
        if (holderBlockGate.isBlocked(entity.getId(), normalizedWallet)) {
            reasons.add("(or its wallet) is subject to an active §16 eWpG Sperrvermerk (legal block)");
        }
        return reasons;
    }
}
