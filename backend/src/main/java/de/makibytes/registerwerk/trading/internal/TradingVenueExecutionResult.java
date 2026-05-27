package de.makibytes.registerwerk.trading.internal;

record TradingVenueExecutionResult(String externalOrderId, Status status, String errorMessage) {

    enum Status { PENDING, FILLED, REJECTED }

    static TradingVenueExecutionResult pending(String externalOrderId) {
        return new TradingVenueExecutionResult(externalOrderId, Status.PENDING, null);
    }

    static TradingVenueExecutionResult filled(String externalOrderId) {
        return new TradingVenueExecutionResult(externalOrderId, Status.FILLED, null);
    }

    static TradingVenueExecutionResult rejected(String reason) {
        return new TradingVenueExecutionResult(null, Status.REJECTED, reason);
    }
}
