package de.makibytes.registerwerk.finality.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReorgObservation v1 lineage and severity invariants")
class ReorgObservationTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final ReorgObservation.BlockReference ANCESTOR = block(
            99, "0x99", "0x98", FinalityLevel.SAFE);

    @Test
    void routineRequiresTwoRootedContiguousLineages() {
        assertThatCode(() -> resolved(
                ReorgObservation.ReorgSeverity.ROUTINE,
                List.of(block(100, "0xa0", "0x99", FinalityLevel.SAFE),
                        block(101, "0xa1", "0xa0", FinalityLevel.SAFE)),
                List.of(block(100, "0xb0", "0x99", FinalityLevel.PROVISIONAL),
                        block(101, "0xb1", "0xb0", FinalityLevel.PROVISIONAL))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsHeightGapsAndDuplicateHeights() {
        assertThatThrownBy(() -> resolved(
                ReorgObservation.ReorgSeverity.ROUTINE,
                List.of(block(100, "0xa0", "0x99", FinalityLevel.SAFE),
                        block(102, "0xa2", "0xa0", FinalityLevel.SAFE)),
                List.of(block(100, "0xb0", "0x99", FinalityLevel.PROVISIONAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    void rejectsLineageNotRootedAtCommonAncestor() {
        assertThatThrownBy(() -> resolved(
                ReorgObservation.ReorgSeverity.ROUTINE,
                List.of(block(100, "0xa0", "0xwrong", FinalityLevel.SAFE)),
                List.of(block(100, "0xb0", "0x99", FinalityLevel.PROVISIONAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rooted at commonAncestor");
    }

    @Test
    void routineCannotOrphanFinalizedBlock() {
        assertThatThrownBy(() -> resolved(
                ReorgObservation.ReorgSeverity.ROUTINE,
                List.of(block(100, "0xa0", "0x99", FinalityLevel.FINALIZED)),
                List.of(block(100, "0xb0", "0x99", FinalityLevel.PROVISIONAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routine reorg");
    }

    @Test
    void finalityViolationMustIdentifyFinalizedOrphan() {
        assertThatThrownBy(() -> resolved(
                ReorgObservation.ReorgSeverity.FINALITY_VIOLATION,
                List.of(block(100, "0xa0", "0x99", FinalityLevel.SAFE)),
                List.of(block(100, "0xb0", "0x99", FinalityLevel.PROVISIONAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalized orphan");
    }

    @Test
    void unresolvedAncestryHasNoClaimedAncestorOrOrphansAndHasReplacementEvidence() {
        assertThatCode(() -> new ReorgObservation(
                "1", "unresolved", ReorgObservation.ReorgSeverity.UNRESOLVED_ANCESTRY,
                null, List.of(),
                List.of(block(200, "0xb0", "0xunknown", FinalityLevel.PROVISIONAL)),
                OBSERVED_AT)).doesNotThrowAnyException();

        assertThatThrownBy(() -> new ReorgObservation(
                "1", "unresolved", ReorgObservation.ReorgSeverity.UNRESOLVED_ANCESTRY,
                null, List.of(block(199, "0xa0", "0xunknown", FinalityLevel.SAFE)),
                List.of(block(200, "0xb0", "0xunknown", FinalityLevel.PROVISIONAL)),
                OBSERVED_AT)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty orphaned lineage");

        assertThatThrownBy(() -> new ReorgObservation(
                "1", "unresolved", ReorgObservation.ReorgSeverity.UNRESOLVED_ANCESTRY,
                null, List.of(), List.of(), OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty replacement lineage");
    }

    private static ReorgObservation resolved(
            ReorgObservation.ReorgSeverity severity,
            List<ReorgObservation.BlockReference> orphaned,
            List<ReorgObservation.BlockReference> replacements) {
        return new ReorgObservation("1", "episode", severity, ANCESTOR,
                orphaned, replacements, OBSERVED_AT);
    }

    private static ReorgObservation.BlockReference block(
            long number, String hash, String parentHash, FinalityLevel finality) {
        return new ReorgObservation.BlockReference(number, hash, parentHash, finality);
    }
}
