package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.finality.api.ReorgObservation;

import java.util.List;
import java.util.UUID;

/**
 * Applies an exact Chaincache reorg lineage to projections owned by another module.
 *
 * <p>The port belongs to the calling blockchain module so durable stream ingestion does not
 * depend on an indexer implementation package and create a module cycle.</p>
 */
public interface ReorgProjectionPort {

    AppliedReorg apply(UUID chainConfigId, ReorgObservation observation);

    record AppliedReorg(int orphanedTransferCount, List<UUID> affectedAssetIds,
            boolean blockedByLocalFinality) {
        public AppliedReorg(int orphanedTransferCount, List<UUID> affectedAssetIds) {
            this(orphanedTransferCount, affectedAssetIds, false);
        }

        public AppliedReorg {
            affectedAssetIds = List.copyOf(affectedAssetIds);
        }
    }
}
