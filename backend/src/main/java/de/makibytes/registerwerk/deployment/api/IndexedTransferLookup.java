package de.makibytes.registerwerk.deployment.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view of what the chain indexer has recorded as FINAL (Wave 0b C7), for modules that must not depend on the
 * indexer: {@code asset} cannot import {@code indexer} (indexer -> lending -> asset would close a module cycle), so
 * this port lives here and is implemented by {@code indexer.internal.IndexedTransferLookupService}.
 *
 * <p>A transaction's business effect is only believed once its transfer is indexed as FINALIZED: the transaction
 * receipt says the call did not revert, the indexed transfer says the units actually moved.
 */
public interface IndexedTransferLookup {

    /**
     * @param deploymentId the deployment the transfer was linked to, or null when it is not linked yet
     * @param mint         the transfer creates units (from the zero address / event type MINT)
     * @param burn         the transfer destroys units (to the zero address / event type BURN)
     */
    record IndexedTransfer(UUID deploymentId, String fromAddress, String toAddress, BigDecimal amount,
                           boolean mint, boolean burn) {
    }

    /** The FINALIZED transfers of one transaction (hash compared case-insensitively); empty when none is indexed yet. */
    List<IndexedTransfer> finalizedTransfers(String txHash);

    /**
     * The net FINALIZED balance of {@code walletAddress} on one deployment (units received minus units sent; mint and
     * burn counterparties are the zero address). Zero when the wallet never appeared.
     */
    BigDecimal finalizedBalance(UUID deploymentId, String walletAddress);
}
