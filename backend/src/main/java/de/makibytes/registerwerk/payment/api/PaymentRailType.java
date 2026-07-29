package de.makibytes.registerwerk.payment.api;

/**
 * The kinds of payment rails the registry operator curates for the cash leg:
 * <ul>
 *   <li>{@code STABLECOIN} — an operator-curated token whose classification, issuer status,
 *       redemption terms and operator-entered MiCAR-related attestations require independent
 *       verification; moved technically as a plain ERC-20 transfer;</li>
 *   <li>{@code PONTES_API} — off-chain instant payment triggered through the Pontes API;</li>
 *   <li>{@code ERC7573_DVP} — same-transaction delivery-versus-payment through the operator's
 *       DvpSettlement contract; exact-leg behavior, chain finality and legal-register
 *       reconciliation require separate verification;</li>
 *   <li>{@code OFFCHAIN_SEPA} — classic SEPA transfer reconciled off-chain.</li>
 * </ul>
 */
public enum PaymentRailType {
    STABLECOIN,
    PONTES_API,
    ERC7573_DVP,
    OFFCHAIN_SEPA;

    /**
     * Whether this rail type settles onchain and therefore needs a deployed contract
     * address per chain ({@link PaymentRailChainAddress}) before a dApp on that chain
     * may declare it — as opposed to off-chain rails (Pontes API, SEPA), which need none.
     */
    public boolean isChainBound() {
        return this == STABLECOIN || this == ERC7573_DVP;
    }
}
