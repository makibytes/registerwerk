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
    CLOSED
}
