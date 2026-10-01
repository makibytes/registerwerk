package de.makibytes.registerwerk.trading.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * SRE report for the first run after the 5A-03 rollout: what the timeout job is about to move.
 * {@code overdueAwaiting} trades go to PAYMENT_UNRESOLVED (and page the operator);
 * {@code failedAfterDeclaredPayment} are historic FAILED rows that had a declared payment and are
 * only listed for review - never changed automatically.
 */
public record TimeoutBacklogResponse(
        long overduePending,
        long overdueAwaiting,
        long unresolvedTotal,
        long unresolvedAged,
        List<UUID> failedAfterDeclaredPayment) {
}
