package de.makibytes.registerwerk.finality.api;

/**
 * Three-tier on-chain finality, shared by every module that persists or reasons about state
 * derived from an indexed on-chain event. Replaces the two-tier {@code TokenTransfer.FinalityStatus}
 * (PROVISIONAL/FINAL/ORPHANED) that used to live in {@code indexer.api} — FINAL folded SAFE and
 * FINALIZED together, which meant the registry could never express a chain's real intermediate
 * finality guarantee (e.g. Ethereum's {@code safe} tag, Starknet's ACCEPTED_ON_L2).
 *
 * <p>Aligns with the sibling product chaincache's {@code BlockFinality} (PROVISIONAL/SAFE/
 * FINALIZED, tracked separately per-chain) and chaincheck's {@code Confidence}
 * (NEW/SAFE/FINALIZED) — see the portfolio-strategy plan's "not recommended" section for why the
 * three products still don't share a library: they compute genuinely different aggregates over
 * the same vocabulary.
 *
 * <p>{@code ORPHANED} is a terminal negative verdict, not a lattice position — a reorged-out row
 * is kept (never deleted, this is a regulated register with an audit-trail requirement) but must
 * never be read as satisfying any required level, including its own former one.
 */
public enum FinalityLevel {
    PROVISIONAL,
    SAFE,
    FINALIZED,
    ORPHANED;

    /** True if this level is at least as strong as {@code required} — always false when either
     *  side is {@code ORPHANED}, since orphaned rows satisfy nothing and nothing requires being
     *  orphaned. */
    public boolean atLeast(FinalityLevel required) {
        if (this == ORPHANED || required == ORPHANED) {
            return false;
        }
        return rank(this) >= rank(required);
    }

    private static int rank(FinalityLevel level) {
        return switch (level) {
            case PROVISIONAL -> 0;
            case SAFE -> 1;
            case FINALIZED -> 2;
            case ORPHANED -> -1;
        };
    }

    /** The customer/trader-facing plain-language vocabulary — "Two vocabularies, one model,
     *  resolved server-side by principal role so the frontend cannot violate it" (portfolio plan).
     *  Technical-role callers (REGISTRY_ADMIN/AUDIT/COMPLIANCE_OFFICER/RELATIONSHIP_MANAGER) see
     *  {@link #name()} instead — see {@code indexer.web.TokenTransferMapper}, the first real
     *  consumer of this split. */
    public String plainLabel() {
        return switch (this) {
            case PROVISIONAL -> "Being confirmed";
            case SAFE -> "Confirmed";
            case FINALIZED -> "Settled — final";
            case ORPHANED -> "Did not go through";
        };
    }
}
