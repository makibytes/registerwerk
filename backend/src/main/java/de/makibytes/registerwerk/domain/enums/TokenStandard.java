package de.makibytes.registerwerk.domain.enums;

public enum TokenStandard {
    ERC20,
    ERC721,
    ERC1155,
    ERC3643,       // T-REX regulated security token
    CONF_ERC20,    // ERC-7984 confidential fungible token (Zama fhEVM)
    CONF_ERC3643,  // Confidential regulated security token (Zama fhEVM + T-REX)
    SPL            // Solana Program Library token
}
