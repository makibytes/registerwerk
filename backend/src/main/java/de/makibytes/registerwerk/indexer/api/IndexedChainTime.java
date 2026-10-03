package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * How far in CHAIN time the indexers have processed the chains of a set of deployments (Wave 0b H7).
 *
 * <p>The corporate-action snapshot used to ask "was the holder sync run after the record date?" - a wall-clock
 * question. A lagging indexer, or a sync that ran against stale data, answered yes although the chain had never been
 * indexed past the record-date cut-off. This answers the right question: the BLOCK time of the head the indexer has
 * processed.
 */
public interface IndexedChainTime {

    /**
     * @return the earliest, over all live (non-FAILED) deployments, of the block time the indexer of that
     *         deployment's chain has processed up to; empty when at least one deployment's chain reports none (the
     *         caller must then fall back to weaker evidence and say so - empty is never "fresh")
     */
    Optional<Instant> indexedThrough(Collection<AssetDeployment> deployments);
}
