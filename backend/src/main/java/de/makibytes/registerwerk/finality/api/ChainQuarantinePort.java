package de.makibytes.registerwerk.finality.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read side for the durable, fail-closed state of a chain whose canonical history is unsafe. */
public interface ChainQuarantinePort {

    Optional<ActiveChainQuarantine> findActive(UUID chainConfigId);

    /**
     * Establishes the linearization point for an irreversible chain submission.
     *
     * <p>The implementation locks the same {@code chain_config} row used by quarantine
     * activation and then checks the active quarantine snapshot. The caller must invoke this
     * from a transaction and retain that transaction until the RPC submission has returned.
     * Consequently either the submission owns the row first and is ordered before quarantine
     * activation, or quarantine owns it first and the submission observes the committed
     * quarantine and fails closed.
     */
    void requireSubmissionAllowed(UUID chainConfigId);

    record ActiveChainQuarantine(
            UUID chainConfigId,
            String reorgId,
            ReorgObservation.ReorgSeverity severity,
            QuarantineTrigger trigger,
            String triggerDetail,
            Instant observedAt,
            Instant activatedAt) {
        public ActiveChainQuarantine(UUID chainConfigId, String reorgId,
                ReorgObservation.ReorgSeverity severity, Instant observedAt, Instant activatedAt) {
            this(chainConfigId, reorgId, severity,
                    severity == ReorgObservation.ReorgSeverity.FINALITY_VIOLATION
                            ? QuarantineTrigger.CONSENSUS_FINALITY_VIOLATION
                            : QuarantineTrigger.UNRESOLVED_ANCESTRY,
                    null, observedAt, activatedAt);
        }
    }
}
