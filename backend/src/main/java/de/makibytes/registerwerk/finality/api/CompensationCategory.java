package de.makibytes.registerwerk.finality.api;

/**
 * How a {@link ChainEffectCompensator} undoes its effect when the source block is orphaned. See
 * the {@code finality} module's architecture notes (plan doc) for the full assignment of every
 * effect type to a category — this enum is deliberately just the three shapes, not a registry.
 */
public enum CompensationCategory {

    /** The state is a pure aggregate over still-current source rows (e.g. {@code asset_holder}
     *  over FINALIZED {@code token_transfer} rows) — undoing means re-running the same
     *  derivation, not restoring a snapshot. */
    RECOMPUTE,

    /** The state is a one-way flip (PENDING to CONFIRMED, granted to active, etc.) that cannot be
     *  re-derived from anything else — undoing means restoring a prior value, using the effect's
     *  optional {@code before_state} snapshot. */
    INVERSE_FLIP,

    /** The effect left the system's boundary (a document already emailed, a webhook already
     *  delivered) and cannot be undone at all — the compensator's only valid response is
     *  {@link CompensationOutcome.Irreversible}. */
    IRREVERSIBLE
}
