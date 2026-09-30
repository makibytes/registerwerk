package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BalanceDeltaPairing")
class BalanceDeltaPairingTest {

    private static Map<String, BigDecimal> deltas(Object... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], new BigDecimal(kv[i + 1].toString()));
        }
        return m;
    }

    @Test
    @DisplayName("a split (sender 100 -> 60 receiver, 40 change) nets to one transfer of 60")
    void split() {
        List<BalanceDeltaPairing.Leg> legs = BalanceDeltaPairing.pair(deltas("A", -60, "B", 60));
        assertThat(legs).containsExactly(new BalanceDeltaPairing.Leg("A", "B", new BigDecimal("60"), TokenTransfer.EventType.TRANSFER));
    }

    @Test
    @DisplayName("a merge of one owner's holdings nets to zero and books nothing")
    void merge() {
        assertThat(BalanceDeltaPairing.pair(deltas("A", 0))).isEmpty();
    }

    @Test
    @DisplayName("residual positive is a MINT, residual negative a BURN, and the legs reproduce every delta")
    void residuals() {
        List<BalanceDeltaPairing.Leg> legs = BalanceDeltaPairing.pair(deltas("A", -100, "B", 70, "C", 50));
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        for (BalanceDeltaPairing.Leg l : legs) {
            if (l.from() != null) net.merge(l.from(), l.amount().negate(), BigDecimal::add);
            if (l.to() != null) net.merge(l.to(), l.amount(), BigDecimal::add);
        }
        assertThat(net.get("A")).isEqualByComparingTo("-100");
        assertThat(net.get("B")).isEqualByComparingTo("70");
        assertThat(net.get("C")).isEqualByComparingTo("50");
        assertThat(legs).extracting(BalanceDeltaPairing.Leg::type).contains(TokenTransfer.EventType.MINT).doesNotContain(TokenTransfer.EventType.BURN);
        List<BalanceDeltaPairing.Leg> burn = BalanceDeltaPairing.pair(deltas("A", -100, "B", 60));
        assertThat(burn).extracting(BalanceDeltaPairing.Leg::type)
                .containsExactly(TokenTransfer.EventType.TRANSFER, TokenTransfer.EventType.BURN);
        assertThat(burn.get(1).amount()).isEqualByComparingTo("40");
    }
}
