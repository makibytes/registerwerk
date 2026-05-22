package de.makibytes.registerwerk.blockchain.web.dto;

import java.util.UUID;

/**
 * Returned by every admin endpoint that submits an on-chain transaction.
 * The client should poll {@code GET /api/v1/transactions/{txId}} to watch progress.
 */
public record TxSubmissionResponse(UUID txId) {}
