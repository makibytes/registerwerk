package de.makibytes.registerwerk.registertransfer.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired for every §§21/22 eWpG register-transfer lifecycle transition: initiate, export
 * (§20 eWpRV data package), record on-chain handover, complete, cancel. Previously this
 * whole module built rich before/after snapshots (see {@code RegisterTransferService})
 * but never published anything to the audit log — {@code transferId} keys every entry so
 * the full lifecycle of one successor-operator handover can be reconstructed from
 * {@code audit_event} alone.
 */
public record RegisterTransferEvent(
        UUID transferId, String stage, UUID actorId, String actorRole, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "REGISTER_TRANSFER_" + stage; }
    public String subjectType() { return "RegisterTransfer"; }
    public UUID   subjectId()   { return transferId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
