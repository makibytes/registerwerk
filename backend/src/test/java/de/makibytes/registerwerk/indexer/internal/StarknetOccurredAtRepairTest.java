package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StarknetOccurredAtRepair: bounded rescan")
class StarknetOccurredAtRepairTest {

    @Test
    @DisplayName("once a chain has nothing left to repair, later scheduled passes do not query it again")
    void emptyScanIsNotRepeated() {
        ChainConfigRepository chains = mock(ChainConfigRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ChainConfig chain = new ChainConfig();
        chain.setId(UUID.randomUUID());
        chain.setIdentifier("STARKNET_TESTNET");
        when(chains.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET)).thenReturn(List.of(chain));
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());
        StarknetOccurredAtRepair repair = new StarknetOccurredAtRepair(chains, jdbc, RestClient.builder(), true);

        assertThat(repair.repairAll()).isZero();
        assertThat(repair.repairAll()).isZero();
        assertThat(repair.repairAll()).isZero();

        verify(jdbc, times(1)).queryForList(anyString(), eq(Long.class), any(Object[].class));
    }
}
