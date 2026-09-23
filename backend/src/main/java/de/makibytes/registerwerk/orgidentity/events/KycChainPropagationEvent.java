package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Outcome of one pass pushing a KYC lapse (expiry or rejection) of a legal entity onto one chain:
 * org suspension plus KYC/AML claim revocation. {@code details.status} is COMPLETED, FAILED or
 * SUPERSEDED. The individual on-chain steps are audited by their own events
 * ({@code ORG_SUSPENDED}, {@code CLAIM_REVOKED}).
 */
public record KycChainPropagationEvent(UUID legalEntityId, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "KYC_CHAIN_PROPAGATION"; }
    public String subjectType() { return "LegalEntity"; }
    public UUID   subjectId()   { return legalEntityId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
