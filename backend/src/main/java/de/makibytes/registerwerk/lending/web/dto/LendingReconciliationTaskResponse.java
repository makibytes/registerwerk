package de.makibytes.registerwerk.lending.web.dto;

import de.makibytes.registerwerk.lending.api.LendingReconciliationTask;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * Operator work item for collateral that left a lending market outside repay/liquidate.
 * {@code suggestedCalls} spells out the on-chain calls the operator needs.
 */
public record LendingReconciliationTaskResponse(
        UUID id, UUID marketId, String status, String source, BigInteger shortfall, String tokenAdminMethod,
        String detail, Instant detectedAt, String reconcileTxHash, String borrowerWallet,
        BigInteger attributedAmount, String forcedTransferRef, String suggestedCalls) {

    public static LendingReconciliationTaskResponse of(LendingReconciliationTask t, String marketAddress) {
        return new LendingReconciliationTaskResponse(t.getId(), t.getMarketId(), t.getStatus().name(),
                t.getSource().name(), t.getShortfall(), t.getTokenAdminMethod(), t.getDetail(), t.getDetectedAt(),
                t.getReconcileTxHash(), t.getBorrowerWallet(), t.getAttributedAmount(), t.getForcedTransferRef(),
                "market " + marketAddress + ": setBorrowPaused(true); reconcileCollateral(borrower, "
                        + "attributableCollateral, forcedTransferRef)");
    }
}
