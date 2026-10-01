package de.makibytes.registerwerk.repo.api;

public final class RepoTypes {
    private RepoTypes() {}

    public enum Side { BORROW_CASH, LEND_CASH }
    public enum Visibility { TARGETED, BROADCAST }
    public enum RfqStatus { OPEN, MATCHED, CANCELLED, EXPIRED }
    public enum QuoteStatus { ACTIVE, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED, SUPERSEDED }
    public enum SettlementMethod { DVP, FOP }
    public enum TradeStatus {
        PENDING_OPEN_SETTLEMENT, OPEN, MARGIN_CALL, PENDING_CLOSE, DISPUTED, CLOSED, DEFAULTED, CANCELLED;

        /** Statuses in which the collateral is committed (encumbrance, redemption blocker). */
        public static final java.util.List<TradeStatus> OPEN_STATES = java.util.List.of(
                PENDING_OPEN_SETTLEMENT, OPEN, MARGIN_CALL, PENDING_CLOSE, DISPUTED);
    }
    public enum SubstitutionStatus { PENDING, APPROVED, COMPLETED, REJECTED, WITHDRAWN, EXPIRED }
    /** Why a default can be noticed/declared; the debtor is derived from it. */
    public enum DefaultGround { MARGIN_NOT_MET, REPURCHASE_UNPAID, COLLATERAL_RETURN_FAILURE }
    public enum DisputeResolution { RESUME, CLOSE, CANCEL }
    public enum LifecycleEventType {
        TRADE_CONFIRMED,
        OPEN_CASH_CONFIRMED, OPEN_COLLATERAL_CONFIRMED, OPEN_SETTLED,
        MARGIN_CALL, MARGIN_SATISFIED,
        SUBSTITUTION_REQUESTED, SUBSTITUTION_APPROVED, SUBSTITUTION_REJECTED,
        CLOSE_INITIATED, CLOSE_CASH_CONFIRMED, CLOSE_COLLATERAL_CONFIRMED, CLOSED,
        DEFAULT_DECLARED,
        CASH_SENT, COLLATERAL_SENT, MARGIN_DELIVERED, MARGIN_DISPUTED,
        DEFAULT_NOTICE, DISPUTE_OPENED, DISPUTE_RESOLVED, EVIDENCE_NOTE,
        SUBSTITUTION_WITHDRAWN, SUBSTITUTION_EXPIRED, SUBSTITUTION_REPLACEMENT_RECEIVED,
        SUBSTITUTION_ORIGINAL_RETURNED, SUBSTITUTION_COMPLETED,
        PARTY_FLAGGED, CORPORATE_ACTION_DURING_TERM
    }
}

