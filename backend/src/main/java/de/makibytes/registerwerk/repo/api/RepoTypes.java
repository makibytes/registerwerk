package de.makibytes.registerwerk.repo.api;

public final class RepoTypes {
    private RepoTypes() {}

    public enum Side { BORROW_CASH, LEND_CASH }
    public enum Visibility { TARGETED, BROADCAST }
    public enum RfqStatus { OPEN, MATCHED, CANCELLED, EXPIRED }
    public enum QuoteStatus { ACTIVE, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED }
    public enum SettlementMethod { DVP, FOP }
    public enum TradeStatus {
        PENDING_OPEN_SETTLEMENT, OPEN, MARGIN_CALL, PENDING_CLOSE, CLOSED, DEFAULTED, CANCELLED
    }
    public enum LifecycleEventType {
        TRADE_CONFIRMED,
        OPEN_CASH_CONFIRMED, OPEN_COLLATERAL_CONFIRMED, OPEN_SETTLED,
        MARGIN_CALL, MARGIN_SATISFIED,
        SUBSTITUTION_REQUESTED, SUBSTITUTION_APPROVED, SUBSTITUTION_REJECTED,
        CLOSE_INITIATED, CLOSE_CASH_CONFIRMED, CLOSE_COLLATERAL_CONFIRMED, CLOSED,
        DEFAULT_DECLARED
    }
}

