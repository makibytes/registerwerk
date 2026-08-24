package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/** Audits the exact finalized receipt outcome of an asynchronous org-identity transition. */
public record OrgChainTransitionFinalizedEvent(
        UUID subjectId,
        String subjectType,
        String transition,
        boolean succeeded,
        String txHash,
        long blockNumber,
        String blockHash) implements AuditableEvent {

    @Override public String eventType() { return "ORG_CHAIN_TRANSITION_FINALIZED"; }
    @Override public UUID actorId() { return null; }
    @Override public String actorRole() { return "SYSTEM"; }
    @Override public Map<String, Object> payload() {
        return Map.of(
                "transition", transition,
                "outcome", succeeded ? "SUCCESS" : "FAILED",
                "txHash", txHash,
                "blockNumber", blockNumber,
                "blockHash", blockHash);
    }
}
