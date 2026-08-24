package de.makibytes.registerwerk.finality.api;

import java.time.Instant;
import java.util.List;

/**
 * Versioned, episode-level description of a canonical-chain divergence.
 *
 * <p>The reorg id identifies an occurrence, not merely a pair of lineages: A→B, B→A and a
 * later A→B are three independently compensatable episodes. Lineages are oldest-first.
 */
public record ReorgObservation(
        String schemaVersion,
        String reorgId,
        ReorgSeverity severity,
        BlockReference commonAncestor,
        List<BlockReference> orphanedLineage,
        List<BlockReference> replacementLineage,
        Instant observedAt) {

    public static final String SUPPORTED_SCHEMA_VERSION = "1";

    public ReorgObservation {
        if (!SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported reorg schema version: " + schemaVersion);
        }
        if (reorgId == null || reorgId.isBlank()) {
            throw new IllegalArgumentException("reorgId is required");
        }
        if (severity == null || observedAt == null) {
            throw new IllegalArgumentException("severity and observedAt are required");
        }
        orphanedLineage = List.copyOf(orphanedLineage == null ? List.of() : orphanedLineage);
        replacementLineage = List.copyOf(replacementLineage == null ? List.of() : replacementLineage);
        if (severity == ReorgSeverity.UNRESOLVED_ANCESTRY) {
            if (commonAncestor != null) {
                throw new IllegalArgumentException("An unresolved-ancestry reorg must not claim a common ancestor");
            }
            if (!orphanedLineage.isEmpty() || replacementLineage.isEmpty()) {
                throw new IllegalArgumentException(
                        "An unresolved-ancestry reorg requires an empty orphaned lineage and a non-empty replacement lineage");
            }
            requireLinked(replacementLineage, "replacementLineage", null);
        } else {
            if (commonAncestor == null) {
                throw new IllegalArgumentException("A resolved reorg requires a common ancestor");
            }
            if (orphanedLineage.isEmpty() || replacementLineage.isEmpty()) {
                throw new IllegalArgumentException("A resolved reorg requires non-empty orphaned and replacement lineages");
            }
            requireLinked(orphanedLineage, "orphanedLineage", commonAncestor);
            requireLinked(replacementLineage, "replacementLineage", commonAncestor);

            boolean finalizedOrphan = orphanedLineage.stream()
                    .anyMatch(block -> block.finality() == FinalityLevel.FINALIZED);
            if (severity == ReorgSeverity.ROUTINE && finalizedOrphan) {
                throw new IllegalArgumentException("A routine reorg must not orphan a finalized block");
            }
            if (severity == ReorgSeverity.FINALITY_VIOLATION && !finalizedOrphan) {
                throw new IllegalArgumentException("A finality violation must identify a finalized orphan");
            }
        }
    }

    /** First affected height, when ancestry is known. */
    public Long forkBlockNumber() {
        if (!orphanedLineage.isEmpty()) {
            return orphanedLineage.getFirst().blockNumber();
        }
        return commonAncestor == null ? null : commonAncestor.blockNumber() + 1;
    }

    /** Replacement hash at the fork height, not the replacement tip hash. */
    public String replacementHashAtFork() {
        Long fork = forkBlockNumber();
        if (fork == null) {
            return null;
        }
        return replacementLineage.stream()
                .filter(block -> block.blockNumber() == fork)
                .map(BlockReference::blockHash)
                .findFirst()
                .orElse(null);
    }

    private static void requireLinked(
            List<BlockReference> lineage, String field, BlockReference commonAncestor) {
        long expectedNumber = commonAncestor == null
                ? (lineage.isEmpty() ? 0 : lineage.getFirst().blockNumber())
                : commonAncestor.blockNumber() + 1;
        String expectedParentHash = commonAncestor == null ? null : commonAncestor.blockHash();

        for (BlockReference block : lineage) {
            if (block.blockNumber() != expectedNumber) {
                throw new IllegalArgumentException(field + " must be contiguous and ordered oldest-first");
            }
            if (block.parentHash() == null || block.parentHash().isBlank()) {
                throw new IllegalArgumentException(field + " entries require parentHash");
            }
            if (expectedParentHash != null && !BlockIdentity.sameHash(expectedParentHash, block.parentHash())) {
                throw new IllegalArgumentException(field + " must be parent-linked and rooted at commonAncestor");
            }
            expectedNumber++;
            expectedParentHash = block.blockHash();
        }
    }

    public enum ReorgSeverity {
        ROUTINE,
        FINALITY_VIOLATION,
        UNRESOLVED_ANCESTRY
    }

    public record BlockReference(long blockNumber, String blockHash, String parentHash, FinalityLevel finality) {
        public BlockReference {
            if (blockNumber < 0 || blockHash == null || blockHash.isBlank() || finality == null) {
                throw new IllegalArgumentException("blockNumber, blockHash, and finality are required");
            }
            if (finality == FinalityLevel.ORPHANED) {
                throw new IllegalArgumentException("A reorg block reference carries its pre-reorg finality, not ORPHANED");
            }
        }
    }
}
