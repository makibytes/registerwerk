package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.internal.CantonHoldingMovements.Holding;
import de.makibytes.registerwerk.indexer.internal.CantonHoldingMovements.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** P4D-5: Canton create/archive sequences of split, merge, transfer, issuance and redemption updates. */
@DisplayName("CantonHoldingMovements - create/archive sequences")
class CantonHoldingMovementsTest {

    private static Holding h(String cid, String owner, String amount) {
        return new Holding(cid, owner, "INSTR", new BigDecimal(amount));
    }

    private static Plan plan(List<Holding> creates, List<String> archived, Holding... snapshots) {
        Map<String, Holding> byId = new java.util.HashMap<>();
        for (Holding s : snapshots) byId.put(s.contractId(), s);
        return CantonHoldingMovements.plan(creates, archived, id -> Optional.ofNullable(byId.get(id)));
    }

    @Test
    @DisplayName("split: A's 100 archived, 60 created for B and 40 change for A -> one TRANSFER A->B 60, nothing else")
    void split() {
        Plan p = plan(List.of(h("c-b", "B", "60"), h("c-a2", "A", "40")), List.of("c-a1"), h("c-a1", "A", "100"));

        assertThat(p.movements()).hasSize(1);
        CantonHoldingMovements.Movement m = p.movements().get(0);
        assertThat(m.type()).isEqualTo(TokenTransfer.EventType.TRANSFER);
        assertThat(m.from()).isEqualTo("A");
        assertThat(m.to()).isEqualTo("B");
        assertThat(m.amount()).isEqualByComparingTo("60");
        assertThat(p.consumed()).containsExactly("c-a1");
        assertThat(p.survivors()).extracting(Holding::contractId).containsExactly("c-b", "c-a2");
    }

    @Test
    @DisplayName("merge: 50 + 50 of one owner archived, 100 created -> no movement, snapshot replaced")
    void merge() {
        Plan p = plan(List.of(h("c-m", "A", "100")), List.of("c-1", "c-2"), h("c-1", "A", "50"), h("c-2", "A", "50"));

        assertThat(p.movements()).isEmpty();
        assertThat(p.consumed()).containsExactly("c-1", "c-2");
        assertThat(p.survivors()).extracting(Holding::contractId).containsExactly("c-m");
    }

    @Test
    @DisplayName("issuance is a MINT; redemption is a BURN; both without phantom counter rows")
    void mintAndBurn() {
        Plan mint = plan(List.of(h("c-new", "B", "25")), List.of());
        assertThat(mint.movements()).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo(TokenTransfer.EventType.MINT);
            assertThat(m.from()).isNull();
            assertThat(m.to()).isEqualTo("B");
        });
        Plan burn = plan(List.of(), List.of("c-b"), h("c-b", "B", "25"));
        assertThat(burn.movements()).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo(TokenTransfer.EventType.BURN);
            assertThat(m.from()).isEqualTo("B");
            assertThat(m.to()).isNull();
        });
    }

    @Test
    @DisplayName("a multi-recipient distribution yields several rows with distinct indexes")
    void distributionHasDistinctIndexes() {
        Plan p = plan(List.of(h("c-b", "B", "30"), h("c-c", "C", "20")), List.of("c-a"), h("c-a", "A", "50"));

        assertThat(p.movements()).extracting(CantonHoldingMovements.Movement::index).containsExactly(0, 1);
        assertThat(p.movements()).extracting(CantonHoldingMovements.Movement::to).containsExactly("B", "C");
    }

    @Test
    @DisplayName("an archive with no snapshot is reported and books nothing (no zero-amount phantom BURN)")
    void unresolvedArchive() {
        Plan p = plan(List.of(), List.of("c-unknown"));

        assertThat(p.movements()).isEmpty();
        assertThat(p.unresolved()).containsExactly("c-unknown");
        assertThat(p.consumed()).isEmpty();
    }

    @Test
    @DisplayName("a holding created and archived inside the same update never touches the books")
    void ephemeralHolding() {
        Plan p = plan(List.of(h("c-tmp", "A", "10")), List.of("c-tmp"));

        assertThat(p.movements()).isEmpty();
        assertThat(p.survivors()).isEmpty();
        assertThat(p.unresolved()).isEmpty();
    }
}
