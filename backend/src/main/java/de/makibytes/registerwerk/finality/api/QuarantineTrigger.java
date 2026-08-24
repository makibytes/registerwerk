package de.makibytes.registerwerk.finality.api;

/** Durable operator-facing reason why a reorg episode activated chain quarantine. */
public enum QuarantineTrigger {
    CONSENSUS_FINALITY_VIOLATION,
    UNRESOLVED_ANCESTRY,
    LOCAL_FINALITY_CONFLICT,
    INDEXER_COMPENSATION_FAILED,
    DOMAIN_COMPENSATION_FAILED,
    REORG_ID_COLLISION
}
