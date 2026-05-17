package de.makibytes.registerwerk.blockchain.web.dto;

/**
 * Response for Canton Ledger API operations. Contains the Ledger API update ID
 * (analogous to an EVM transaction hash or Solana signature).
 */
public record CantonUpdateResponse(String updateId) {}
