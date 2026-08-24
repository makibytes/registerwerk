package de.makibytes.registerwerk.finality.api;

/**
 * Implemented by whichever domain module owns an effect type, and discovered by
 * {@code CompensationDispatcher} (in {@code finality.internal}) via Spring
 * {@code List<ChainEffectCompensator>} collection injection — the same inversion already used by
 * {@code screening.internal.ScreeningGateImpl} for {@code SanctionsScreeningPort}. This is how
 * ~8 domain modules can be undone by {@code finality} without {@code finality} ever importing
 * them at compile time: the dependency runs the other way, module → finality.api, and finality
 * only reaches back in at runtime through this interface.
 *
 * <p>Compensators must be idempotent: {@link #compensate} may be called more than once for the
 * same {@link ChainEffectRecord} (retry job, at-least-once event redelivery), and a compensator
 * that finds the effect already undone must return {@link CompensationOutcome.NotApplicable}, not
 * repeat the undo or throw.
 */
public interface ChainEffectCompensator {

    /** The {@code effectType} this compensator handles — must be unique across all registered
     *  compensators; the dispatcher's {@code ApplicationReadyEvent} self-check flags duplicates
     *  and gaps. */
    String effectType();

    /** How this compensator undoes its effect — must match what {@link ChainEffectDescriptor}s
     *  for this {@code effectType} were recorded with. */
    CompensationCategory category();

    /** Undo the effect described by {@code effect}. Never throws — a compensator that fails must
     *  catch its own exception and return {@link CompensationOutcome.Failed} so the dispatcher can
     *  record a reason and schedule a retry rather than losing the failure to an uncaught
     *  exception. */
    CompensationOutcome compensate(ChainEffectRecord effect);
}
