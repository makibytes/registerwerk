package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A Canton bond deployment's stored contract id was re-pointed to the live contract found in
 * the participant's active contract set, because the stored id had been archived by a consuming
 * lifecycle choice without the backend recording the successor (pre-fix deployments, or a
 * ledger commit whose follow-up DB write failed). {@code trigger} is {@code "STARTUP"} for the
 * boot-time reconciliation and {@code "CONTRACT_NOT_FOUND"} for the on-demand self-heal.
 */
public record CantonBondContractIdRepairedEvent(
        UUID deploymentId, String previousContractId, String contractId, String trigger)
        implements AuditableEvent {
    public String eventType()   { return "CANTON_BOND_CONTRACT_ID_REPAIRED"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() {
        return Map.of(
                "previousContractId", previousContractId != null ? previousContractId : "",
                "contractId", contractId,
                "trigger", trigger);
    }
}
