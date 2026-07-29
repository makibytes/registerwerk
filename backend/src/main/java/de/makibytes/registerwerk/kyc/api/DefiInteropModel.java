package de.makibytes.registerwerk.kyc.api;

/**
 * Permitted DeFi-interoperability model for assets issued under a jurisdiction.
 * Reflects that MiCAR excludes MiFID II financial instruments (Art. 2(4)) — the constraint
 * on tokenized securities is MiFID II / national securities law, not MiCAR, so pooled
 * liquidity is only viable through a licensed intermediary, never an anonymous permissionless
 * pool.
 */
public enum DefiInteropModel {

    /** No pooled or bridged DeFi participation permitted for this jurisdiction's assets. */
    NONE,

    /**
     * Pooled/secondary-market participation is permitted only through a regulated nominee or
     * custodian holding the security in a single onboarded address on behalf of investors it
     * KYCs and tracks in its own sub-ledger (eWpG Sammelverwahrung / TVTG token-container /
     * CSSF-AMF custodian-equivalent structures). See {@code ClaimTopic.NOMINEE} and
     * {@code EwpgComplianceModule.isNomineePool}.
     */
    NOMINEE_POOL,

    /**
     * The security token itself never leaves the permissioned perimeter, but external
     * protocols may read {@code PermissionOracle} to gate their own, separate pools to
     * Registerwerk-verified investors. Zero custody risk since no token or fund is ever held
     * by the querying protocol.
     */
    ORACLE_ONLY
}
