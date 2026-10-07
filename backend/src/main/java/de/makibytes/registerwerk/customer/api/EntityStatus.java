package de.makibytes.registerwerk.customer.api;

public enum EntityStatus {
    PENDING_ONBOARDING,
    ACTIVE,
    SUSPENDED,
    /** Merged into / absorbed by another entity — an M&amp;A outcome, not a customer exit. */
    DISSOLVED,
    /**
     * The customer's relationship with this registry has ended in an orderly off-ramp:
     * {@code CustomerOffboardingService.terminate} — users disabled, listings cancelled,
     * ASSET_TOKEN_ADMIN grants revoked, and (for holdings/issuances) portfolio-migration /
     * register-transfer follow-up raised. Distinct from DISSOLVED, which was previously the
     * only terminal state and conflated "the legal entity ceased to exist" with "the customer
     * left this registry" — two very different events with different consequences.
     */
    CLOSED,
    /**
     * Reinstatement of a CLOSED/DISSOLVED entity is under way (T6-12): requested by a 4-eyes
     * decision with a legal reference, never straight back to ACTIVE. The entity stays blocked
     * like any non-ACTIVE entity (trading, settlement, issuance, sessions) until a fresh KYC
     * approval - the existing approve flow with its gates - moves it to ACTIVE. On-chain claims
     * and org membership are not touched in between.
     */
    PENDING_REACTIVATION;

    /**
     * The lifecycle table (6-21). PENDING_ONBOARDING leaves only through onboarding completion
     * (-&gt; ACTIVE); ACTIVE &lt;-&gt; SUSPENDED is the reversible pair; CLOSED is reached only by
     * {@code CustomerOffboardingService.terminate} and DISSOLVED only by a merger. CLOSED and
     * DISSOLVED leave only through a reinstatement request (T6-12) into PENDING_REACTIVATION,
     * which becomes ACTIVE on a fresh KYC approval or CLOSED again when abandoned.
     */
    public boolean canTransitionTo(EntityStatus target) {
        return switch (this) {
            case PENDING_ONBOARDING -> target == ACTIVE;
            case ACTIVE -> target == SUSPENDED || target == CLOSED || target == DISSOLVED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED || target == DISSOLVED;
            case CLOSED, DISSOLVED -> target == PENDING_REACTIVATION;
            case PENDING_REACTIVATION -> target == ACTIVE || target == CLOSED;
        };
    }

    /** CLOSED/DISSOLVED: the relationship has ended (a reinstatement request can reopen it). */
    public boolean isTerminal() {
        return this == CLOSED || this == DISSOLVED;
    }
}
