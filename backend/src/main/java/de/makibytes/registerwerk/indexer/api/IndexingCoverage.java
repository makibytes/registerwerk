package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;

/**
 * P4-01: whether a deployment has <em>reliable ingestion evidence</em>. "No transfer rows" is
 * only trustworthy as "nothing happened" when something is demonstrably indexing the deployment;
 * on a chain without an ingester (or with a dead one) an empty {@code token_transfer} history
 * says nothing, and reconciling a register against it must fail closed.
 */
public interface IndexingCoverage {

    /** Outcome of {@link #evaluate}. {@code reason} is operator-readable and set only when not covered. */
    record Coverage(boolean covered, String reason) {
        public static Coverage ok() {
            return new Coverage(true, null);
        }

        public static Coverage notIndexed(String reason) {
            return new Coverage(false, reason);
        }
    }

    Coverage evaluate(AssetDeployment deployment);
}
