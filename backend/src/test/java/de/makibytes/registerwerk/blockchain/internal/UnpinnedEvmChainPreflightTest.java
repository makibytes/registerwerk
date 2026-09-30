package de.makibytes.registerwerk.blockchain.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Unpinned EVM chain preflight")
class UnpinnedEvmChainPreflightTest {

    @Test
    @DisplayName("the gauge counts enabled EVM chains with confirmed deployments and no chain_id")
    void gaugeReflectsUnpinnedChains() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UnpinnedEvmChainPreflight preflight = new UnpinnedEvmChainPreflight(jdbc, registry);

        when(jdbc.queryForList(eq(UnpinnedEvmChainPreflight.SQL), eq(String.class))).thenReturn(List.of("MY_L2"));
        assertThat(registry.get("registerwerk_evm_chains_unpinned").gauge().value()).isEqualTo(1.0);
        preflight.check(); // logs ERROR, must not throw

        when(jdbc.queryForList(eq(UnpinnedEvmChainPreflight.SQL), eq(String.class))).thenReturn(List.of());
        assertThat(registry.get("registerwerk_evm_chains_unpinned").gauge().value()).isZero();
    }

    @Test
    @DisplayName("a failing query degrades to zero instead of failing startup")
    void queryFailureDoesNotBreakStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(eq(UnpinnedEvmChainPreflight.SQL), eq(String.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        UnpinnedEvmChainPreflight preflight = new UnpinnedEvmChainPreflight(jdbc, new SimpleMeterRegistry());

        assertThat(preflight.unpinnedChains()).isEmpty();
    }
}
