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
    CLOSED;

    /**
     * The lifecycle table (6-21). PENDING_ONBOARDING leaves only through onboarding completion
     * (-&gt; ACTIVE); ACTIVE &lt;-&gt; SUSPENDED is the reversible pair; CLOSED is reached only by
     * {@code CustomerOffboardingService.terminate} and DISSOLVED only by a merger. CLOSED and
     * DISSOLVED are terminal (re-entry is a new onboarding; interim for parked decision T6-12).
     */
    public boolean canTransitionTo(EntityStatus target) {
        return switch (this) {
            case PENDING_ONBOARDING -> target == ACTIVE;
            case ACTIVE -> target == SUSPENDED || target == CLOSED || target == DISSOLVED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED || target == DISSOLVED;
            case CLOSED, DISSOLVED -> false;
        };
    }

    public boolean isTerminal() {
        return this == CLOSED || this == DISSOLVED;
    }
}
