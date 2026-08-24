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
     *       (or when the EUR value is unknown) execution is blocked until Art. 14(5)
     *       ownership/control verification has been completed.</li>
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

    /**
     * Same as {@link #enforceOutbound(UUID, String, String, BigDecimal)}, plus the transfer's
     * real amount in its own native denomination — for callers that have
     * decrypted or otherwise obtained the actual on-chain amount but have no EUR-equivalent
     * valuation available (no FX/price-oracle infrastructure exists in this codebase). Recording
     * {@code nativeAmount}/{@code nativeSymbol} gives the audit trail a real, queryable figure
     * instead of the silent {@code null} the 4-arg overload always leaves for confidential
     * (Zama fhEVM) transfers, without mislabelling a token-unit count as if it were EUR.
     *
     * @param amountEur    EUR-equivalent valuation, or {@code null} if unavailable (as above)
     * @param nativeAmount the transfer amount in the asset's own denomination, or {@code null}
     *                     if genuinely unknown (e.g. decryption itself failed)
     * @param nativeSymbol the asset's own ticker/symbol {@code nativeAmount} is denominated in
     */
    default void enforceOutbound(UUID assetId, String fromWallet, String toWallet, BigDecimal amountEur,
                                 BigDecimal nativeAmount, String nativeSymbol) {
        enforceOutbound(assetId, fromWallet, toWallet, amountEur);
    }
}
