package de.makibytes.registerwerk.kyc.api;

import java.util.List;
import java.util.UUID;

/**
 * One eligibility test for a counterparty that is about to trade or pledge registered securities
 * (Phase 5, 5C-03). Shares its checks with {@link OutboundDestinationGate} so the two never
 * drift: entity ACTIVE, KYC APPROVED and not past {@code kycExpiryDate}, no unresolved entity or
 * beneficial-owner screening hit, and no active §16 eWpG Sperrvermerk on the entity or wallet.
 * Used by {@code trading} for buyer and seller; the repo desk reuses it.
 */
public interface PartyEligibilityGate {

    /**
     * Refuses (fail closed) with a {@code ComplianceGateException} when the party is not eligible.
     *
     * @param walletAddress the wallet the party trades with (may be {@code null}: entity-level only)
     * @param purpose       short label used in the refusal message (e.g. "trade settlement")
     */
    void require(UUID entityId, String walletAddress, String purpose);

    /** Same tests, non-throwing: every reason the party is NOT eligible (empty = eligible). */
    List<String> check(UUID entityId, String walletAddress);
}
