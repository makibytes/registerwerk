package de.makibytes.registerwerk.asset.api;

public enum TokenStandard {
    ERC20,
    ERC721,
    ERC1155,
    ERC3643,        // T-REX regulated security token
    CONF_ERC20,     // ERC-7984 confidential fungible token (Zama fhEVM)
    CONF_ERC3643,   // Confidential regulated security token (Zama fhEVM + T-REX)
    SPL,            // Solana Program Library token (classic)
    SPL_2022,       // Solana Token-2022 / Token Extensions
    STARKNET_ERC20, // Cairo ERC-20 on Starknet (deployed via UDC, signed with STARK ECDSA)
    STELLAR_ASSET,  // Stellar classic asset (issued via Horizon, signed with Ed25519)
    CANTON_TOKEN    // Canton / Daml asset (Daml Token Standard CIP-0056)
}
