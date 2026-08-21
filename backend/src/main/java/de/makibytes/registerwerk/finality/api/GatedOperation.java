package de.makibytes.registerwerk.finality.api;

/**
 * A finality-gateable action, derived from real call sites across the modules the gate will
 * eventually be wired into (see the plan's P8 phase — {@code FinalityGate} itself does not exist
 * yet; this enum exists so the policy that will drive it is configurable ahead of time).
 *
 * <p>Every value that emits a document or export leaving the system boundary (register
 * statements/extracts/inspections, tax certificates, corporate-action and trade confirmations,
 * regulatory exports) is a hard floor at {@link FinalityLevel#FINALIZED} (see {@code
 * FinalityPolicyDefaults#hasHardFloor}) — the mechanism that keeps compensation's IRREVERSIBLE
 * category empty (see {@code CompensationCategory}'s javadoc): a document only ever goes out once
 * its source block is trusted not to be reorged away.
 */
public enum GatedOperation {

    // ── registerstatement / registertransfer ────────────────────────────────
    /** Issues the §19 eWpG register statement (PDF + email) — {@code RegisterStatementService}. */
    REGISTER_STATEMENT_ISSUE,
    /** Exports a register extract document — {@code RegisterTransferService}/{@code RegisterExtractRenderer}. */
    REGISTER_EXTRACT_EXPORT,
    /** Finalizes a register transfer's on-chain handover — {@code RegisterTransferService.complete}. */
    REGISTER_TRANSFER_COMPLETE,
    /** Completes a portfolio migration after its on-chain transfer — {@code PortfolioMigrationService.complete}. */
    PORTFOLIO_MIGRATION_COMPLETE,
    /** Produces a fulfilled register-inspection document — {@code RegisterInspectionService.fulfil}. */
    REGISTER_INSPECTION_FULFIL,

    // ── corporateactions ──────────────────────────────────────────────────────
    /** Confirms/writes a balance-dependent corporate-action settlement outcome — {@code CorporateActionSettlementWriter}. */
    CORPORATE_ACTION_SETTLEMENT_CONFIRM,
    /** Generates investor/operator confirmation documents (incl. ISO 20022) — {@code CorporateActionConfirmationService}. */
    CORPORATE_ACTION_CONFIRMATION_EXPORT,
    /** Issues the Steuerbescheinigung tax certificate — {@code SteuerbescheinigungService.generate}. */
    TAX_CERTIFICATE_ISSUE,

    // ── trading ───────────────────────────────────────────────────────────────
    /** Confirms payment/settles a pending trade against a balance — {@code TradingService}. */
    TRADE_SETTLEMENT_CONFIRM,
    /** Renders a trade confirmation document (incl. ISO 20022) — {@code TradingService}. */
    TRADE_CONFIRMATION_EXPORT,

    // ── asset ─────────────────────────────────────────────────────────────────
    /** Transitions an asset to ISSUED, activating on-chain balance semantics — {@code AssetLifecycleService.issue}. */
    ASSET_ISSUE,
    /** Reading a holder's on-chain-derived balance as authoritative — {@code HolderDataService}.
     *  The plan's own worked example: {@code BALANCED} maps this to {@code FINALIZED} (a no-op
     *  vs. today's hard-coded filter); {@code FAST} is what makes "count balances at SAFE" reachable. */
    AUTHORITATIVE_BALANCE,
    /** Allocates a subscription order against (presumably confirmed) issuance capacity — {@code SubscriptionOrderService.allocate}. */
    SUBSCRIPTION_ORDER_ALLOCATE,

    // ── marketplace ───────────────────────────────────────────────────────────
    /** Operator approval gate before a dApp version's on-chain anchor submission — {@code ListingLifecycleService.approve}. */
    DAPP_VERSION_APPROVE,

    // ── erc3643 ───────────────────────────────────────────────────────────────
    /** Issues a KYC/AML claim gating investor trade eligibility — {@code ClaimIssuanceService}. */
    IDENTITY_CLAIM_ISSUE,

    // ── regreporting ──────────────────────────────────────────────────────────
    /** MiFIR/DAC8/regulatory-report submissions leaving the system — {@code MifirReportingService}/{@code Dac8ExportService}. */
    REGULATORY_EXPORT
}
