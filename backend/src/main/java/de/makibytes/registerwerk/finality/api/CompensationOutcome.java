package de.makibytes.registerwerk.finality.api;

/**
 * What happened when a {@link ChainEffectCompensator} was asked to undo one {@link ChainEffectRecord}.
 * Sealed so {@code CompensationDispatcher}'s handling switch stays exhaustive as new outcomes are
 * added.
 */
public sealed interface CompensationOutcome {

    /** The effect was successfully undone (or found already undone — compensators must be
     *  idempotent and return this for a no-op re-run, not {@link Failed}). */
    record Compensated(String detail) implements CompensationOutcome {}

    /** The effect no longer needs undoing — e.g. the entity was independently deleted, or a
     *  concurrent compensation already handled it. Not an error. */
    record NotApplicable(String reason) implements CompensationOutcome {}

    /** The compensator ran but could not undo the effect, and threw or returned a definite
     *  failure — eligible for the retry job. {@code cause} may be null. */
    record Failed(String reason, Throwable cause) implements CompensationOutcome {}

    /** The effect crossed the system boundary and can never be undone — the dispatcher records
     *  this as a terminal state and escalates; it never retries. */
    record Irreversible(String remediationHint) implements CompensationOutcome {}
}
