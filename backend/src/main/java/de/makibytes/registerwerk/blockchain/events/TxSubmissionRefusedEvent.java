package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.util.Map;
import java.util.UUID;

/**
 * A transaction was refused before signing by a hard safety check in {@code EvmContractService}
 * (fee/gas ceiling, unpinned or mismatching chain id). {@code kind} is one of
 * {@code FEE_CEILING}, {@code TIP_CEILING}, {@code GAS_CEILING}, {@code CHAIN_ID_UNPINNED},
 * {@code CHAIN_ID_MISMATCH}. System-originated: there is no actor.
 */
public record TxSubmissionRefusedEvent(UUID chainConfigId, String kind, Map<String, Object> details)
        implements AuditableEvent {

    public String eventType()   { return "TX_SUBMISSION_REFUSED_" + kind; }
    public String subjectType() { return "ChainConfig"; }
    public UUID   subjectId()   { return chainConfigId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
