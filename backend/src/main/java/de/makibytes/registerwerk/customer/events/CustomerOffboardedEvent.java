package de.makibytes.registerwerk.customer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A customer entity has been terminated ({@code CustomerOffboardingService.terminate}) — the
 * off-ramp trigger other modules react to: {@code asset.internal.CustomerOffboardingAssetListener}
 * revokes ASSET_TOKEN_ADMIN grants and flags issuer assets for register-transfer/redemption
 * follow-up; {@code trading.internal.CustomerOffboardingTradingListener} cancels open listings;
 * {@code registertransfer.internal.PortfolioMigrationListener} raises a DRAFT portfolio-migration
 * request for every holding so the operator has a concrete per-position checklist.
 */
public record CustomerOffboardedEvent(UUID entityId, UUID actorId, String actorRole, String reason,
                                      String finalStatus, List<String> acknowledgedObligations)
        implements AuditableEvent {

    /** Termination to CLOSED without acknowledged obligations (kept for callers and tests). */
    public CustomerOffboardedEvent(UUID entityId, UUID actorId, String actorRole, String reason) {
        this(entityId, actorId, actorRole, reason, "CLOSED", List.of());
    }

    public String eventType()   { return "CUSTOMER_OFFBOARDED"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return entityId; }
    public Map<String, Object> payload() {
        return Map.of("reason", reason != null ? reason : "",
                "finalStatus", finalStatus != null ? finalStatus : "CLOSED",
                "acknowledgedObligations", acknowledgedObligations != null ? acknowledgedObligations : List.of());
    }
}
