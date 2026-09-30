package de.makibytes.registerwerk.blockchain.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Signing now pins the transaction chain id to {@code chain_config.chain_id} and refuses every submit on
 * an EVM chain without one ({@code ChainIdMismatchException.unpinned}). This preflight makes that state
 * visible before the first operator action fails: an ERROR log at startup and the gauge
 * {@code registerwerk_evm_chains_unpinned} (enabled EVM chains with a CONFIRMED deployment and a NULL
 * chain_id) that the {@code EvmChainIdUnpinned} alert watches. Plain SQL: it spans the chain and
 * deployment modules and reads two columns only. No automatic backfill exists - the repo has no
 * authoritative identifier-to-chain-id table, and a wrong id would sign for another network.
 */
@Component
class UnpinnedEvmChainPreflight {

    private static final Logger log = LoggerFactory.getLogger(UnpinnedEvmChainPreflight.class);

    static final String SQL = """
            SELECT cc.identifier FROM chain_config cc
             WHERE cc.chain_type = 'EVM' AND cc.enabled = true AND cc.chain_id IS NULL
               AND EXISTS (SELECT 1 FROM asset_deployment ad
                            WHERE ad.chain_config_id = cc.id AND ad.deployment_status = 'CONFIRMED')
             ORDER BY cc.identifier
            """;

    private final JdbcTemplate jdbc;

    UnpinnedEvmChainPreflight(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        Gauge.builder("registerwerk_evm_chains_unpinned", this, p -> p.unpinnedChains().size())
                .description("Enabled EVM chains with CONFIRMED deployments but no chain_config.chain_id "
                        + "(all signing on them is refused)")
                .register(meterRegistry);
    }

    List<String> unpinnedChains() {
        try {
            return jdbc.queryForList(SQL, String.class);
        } catch (RuntimeException e) {
            log.warn("Unpinned-EVM-chain preflight query failed: {}", e.toString());
            return List.of();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        List<String> chains = unpinnedChains();
        if (!chains.isEmpty()) {
            log.error("EVM chain(s) {} have CONFIRMED deployments but no chain_id: every transaction on them is "
                    + "REFUSED (chain id unpinned). Set chain_config.chain_id (PATCH /api/v1/admin/chains/{id} "
                    + "with chainId) to the network's id, then retry.", chains);
        }
    }
}
