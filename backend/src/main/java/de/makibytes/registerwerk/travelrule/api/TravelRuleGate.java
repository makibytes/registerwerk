package de.makibytes.registerwerk.travelrule.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public API façade for the travelrule module.
 * Used by other modules (blockchain, trading) to enforce Travel Rule obligations
 * under Regulation (EU) 2023/1113 (TFR) without crossing into travelrule/internal/.
 */
public interface TravelRuleGate {

    /**
     * Evaluates and dispatches the Travel Rule obligations for an outbound
     * token transfer <em>before</em> the on-chain transaction is submitted.
     *
     * <ul>
     *   <li>Beneficiary wallet belongs to a known CASP/VASP → IVMS-101 message is
     *       dispatched, at any amount (TFR Art. 14–16, no de minimis).</li>
     *   <li>Self-hosted beneficiary → originator info is recorded; above EUR 1,000
     *       (or when the EUR value is unknown) an Art. 14(5) ownership/control
     *       verification flag is set.</li>
     * </ul>
     *
     * @param assetId    the registry asset being transferred
     * @param fromWallet originator wallet address
     * @param toWallet   beneficiary wallet address
     * @param amountEur  EUR equivalent of the transfer, or {@code null} if no
     *                   valuation is available (treated conservatively)
     * @throws IllegalStateException if the transfer must not proceed — e.g. the
     *         beneficiary is a VASP but no Travel Rule protocol adapter is
     *         configured to transmit the legally required information
     */
    void enforceOutbound(UUID assetId, String fromWallet, String toWallet, BigDecimal amountEur);
}
