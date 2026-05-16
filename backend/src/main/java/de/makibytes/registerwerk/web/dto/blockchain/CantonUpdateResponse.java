package de.makibytes.registerwerk.web.dto.blockchain;

/**
 * Response for Canton Ledger API operations. Contains the Ledger API update ID
 * (analogous to an EVM transaction hash or Solana signature).
 */
public record CantonUpdateResponse(String updateId) {}
