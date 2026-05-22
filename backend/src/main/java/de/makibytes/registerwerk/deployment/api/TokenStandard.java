package de.makibytes.registerwerk.deployment.api;

public enum TokenStandard {
    // ── Existing standards ────────────────────────────────────────────────
    ERC20,
    ERC721,
    ERC1155,
    ERC3643,           // T-REX regulated security token
    CONF_ERC20,        // ERC-7984 confidential fungible token (Zama fhEVM)
    CONF_ERC3643,      // Confidential regulated security token (Zama fhEVM + T-REX)
    SPL,               // Solana Program Library token (classic)
    SPL_2022,          // Solana Token-2022 / Token Extensions (no extensions configured)
    STARKNET_ERC20,    // Cairo ERC-20 on Starknet (deployed via UDC, STARK ECDSA signed)
    STELLAR_ASSET,     // Stellar classic asset (Horizon, Ed25519)
    CANTON_TOKEN,      // Canton / Daml generic instrument (Daml Token Standard CIP-0056)

    // ── Securities-grade additions ────────────────────────────────────────
    // EVM (Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism)
    ERC3525,           // Semi-fungible token — slot+value; purpose-built for bonds and fund shares
    ERC4626,           // Tokenized vault (sync) — money-market / bond funds with NAV strikes
    ERC7540,           // Tokenized vault (async) — institutional T+1 settlement via request/claim

    // Starknet
    STARKNET_ERC3525,  // Cairo semi-fungible token (Carbonable-derived), slot+value on Starknet

    // Canton — DAML Finance bond instruments
    DAML_BOND_FIXED,   // Fixed-rate bond via Daml.Finance.Instrument.Bond.FixedRate
    DAML_BOND_FLOATING, // Floating-rate bond via Daml.Finance.Instrument.Bond.FloatingRate
    DAML_BOND_ZERO,    // Zero-coupon bond via Daml.Finance.Instrument.Bond.ZeroCoupon

    // Solana — Token-2022 extension presets
    SPL_2022_BOND,         // Token-2022 + Interest-Bearing + Permanent Delegate + Transfer Hook
    SPL_2022_CONFIDENTIAL  // Token-2022 + Confidential Transfers + Permanent Delegate + Transfer Hook
}
