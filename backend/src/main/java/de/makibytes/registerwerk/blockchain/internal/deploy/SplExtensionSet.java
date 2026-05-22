package de.makibytes.registerwerk.blockchain.internal.deploy;

/**
 * Preset combinations of Token-2022 extensions for Registerwerk securities.
 *
 * <p>Token-2022 extensions are initialized before {@code InitializeMint} in a single transaction.
 * Each preset encodes a fixed, well-reasoned combination of extensions for a specific use case.
 *
 * <pre>
 * NONE          — plain Token-2022, no extensions (equivalent to {@code SPL_2022})
 * BOND          — Interest-Bearing + Permanent Delegate + Transfer Hook + MintCloseAuthority + Metadata
 * CONFIDENTIAL  — Confidential Transfers + Permanent Delegate + Transfer Hook + Metadata
 * </pre>
 *
 * <p><b>BOND preset details:</b>
 * <ul>
 *   <li>{@code InterestBearingMint} — encodes bond coupon accrual natively on the mint</li>
 *   <li>{@code PermanentDelegate} — registry payer can force-transfer/burn (eWpG §24/26)</li>
 *   <li>{@code TransferHook} — Anchor hook program enforces whitelist + AML checks</li>
 *   <li>{@code MintCloseAuthority} — registry can close the mint at maturity (compulsory cancellation)</li>
 *   <li>{@code MetadataPointer} + {@code TokenMetadata} — ISIN, maturity, face value on-chain</li>
 * </ul>
 *
 * <p><b>CONFIDENTIAL preset details:</b>
 * <ul>
 *   <li>{@code ConfidentialTransferMint} — ZK-proofed balances/amounts (ElGamal encryption)</li>
 *   <li>{@code PermanentDelegate} — regulatory force-transfer even in encrypted state</li>
 *   <li>{@code TransferHook} — whitelist enforcement before any transfer</li>
 *   <li>{@code MetadataPointer} + {@code TokenMetadata} — non-sensitive metadata (ISIN, name)</li>
 * </ul>
 */
public enum SplExtensionSet {
    NONE,
    BOND,
    CONFIDENTIAL
}
