package de.makibytes.registerwerk.asset.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Corporate-action facts that asset redemption depends on (T3-01), answered by the
 * {@code corporateactions} module. {@code corporateactions} already depends on {@code asset}, so
 * {@code asset} must not import it (module cycle); this port inverts the dependency — it is
 * implemented by {@code corporateactions.internal.RedemptionReadinessAdapter}.
 */
public interface RedemptionReadinessPort {

    /**
     * The asset's most recently settled retirement action: a REDEMPTION or CALL in SETTLED or
     * CLOSED with at least one entitlement entry. Empty when no such action exists, i.e. the
     * holders have not been paid out and the asset must not be retired.
     */
    Optional<SettledRetirement> settledRetirementAction(UUID assetId);

    /**
     * True while any corporate action on the asset is still in flight (ANNOUNCED,
     * SNAPSHOT_BLOCKED, RECORD_DATE_SET, COMPUTED or AWAITING_SETTLEMENT).
     */
    boolean hasOpenCorporateAction(UUID assetId);

    /**
     * T3-07: every non-terminal corporate action (PROPOSED through AWAITING_SETTLEMENT) - a §§21/22
     * register handover must not overtake a payout that is still owed on this registrar's watch.
     */
    List<OpenAction> openActions(UUID assetId);

    record OpenAction(UUID id, String actionType, String status, LocalDate recordDate, LocalDate paymentDate) {
    }

    /**
     * @param corporateActionId  the settled REDEMPTION/CALL action
     * @param paidWallets        normalised wallets whose entry is PAYABLE and settled — the only
     *                           holders whose tokens redemption may burn
     * @param nominalAtRecord    per paid wallet, the units it held at the record date — the units the redemption
     *                           actually paid for (Wave 0b C7): the burn is capped at it, so units the wallet bought
     *                           after the record date are never destroyed unpaid
     */
    record SettledRetirement(UUID corporateActionId, Set<String> paidWallets, Map<String, BigDecimal> nominalAtRecord) {

        public SettledRetirement(UUID corporateActionId, Set<String> paidWallets) {
            this(corporateActionId, paidWallets, Map.of());
        }
    }
}
