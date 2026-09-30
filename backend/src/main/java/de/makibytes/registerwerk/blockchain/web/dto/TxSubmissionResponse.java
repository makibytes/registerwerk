package de.makibytes.registerwerk.blockchain.web.dto;

import java.util.UUID;

/**
 * Returned by every admin endpoint that submits an on-chain transaction.
 * The client should poll {@code GET /api/v1/transactions/{txId}} to watch progress.
 *
 * <p>{@code destinationHolder} (P4C-2) echoes the register holder the destination address resolved to
 * (whitelist / mint), so the operator can confirm who received the position; null otherwise.
 */
public record TxSubmissionResponse(UUID txId, String destinationHolder) {

    public TxSubmissionResponse(UUID txId) {
        this(txId, null);
    }
}
