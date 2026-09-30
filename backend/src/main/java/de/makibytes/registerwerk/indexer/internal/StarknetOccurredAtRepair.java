package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot, idempotent repair for P4D-3. Starknet rows written before the fix carry the poll time as
 * {@code occurred_at}; record-date snapshots ({@code occurred_at < cutoff}) on such assets depend on
 * indexer latency. For every Starknet row that has a block number but no {@code raw_data.blockTimestamp}
 * (the marker new rows carry) the block header's timestamp is fetched and written back. Rows move to
 * the correct monthly partition automatically. Bounded per pass; the scheduled pass continues until
 * nothing is left, then skips the chain (partial index V25 keeps even the first scan cheap). Run this BEFORE a record-date snapshot is taken on a Starknet asset - snapshots
 * taken earlier may be off (recorded in the phase-4 ledger).
 *
 * <p>Disable with {@code registerwerk.indexer.starknet.repair-occurred-at=false}.
 */
@Component
class StarknetOccurredAtRepair {

    private static final Logger log = LoggerFactory.getLogger(StarknetOccurredAtRepair.class);

    static final int MAX_BLOCKS_PER_PASS = 500;

    private final ChainConfigRepository chainConfigRepository;
    private final JdbcTemplate jdbc;
    private final RestClient restClient;
    private final boolean enabled;
    /** Chains whose scan came back empty. New rows are stamped with the block time at write, so no legacy
     *  (poll-time) row can appear afterwards; the recurring pass then skips the chain until restart. */
    private final Set<UUID> repaired = ConcurrentHashMap.newKeySet();

    StarknetOccurredAtRepair(ChainConfigRepository chainConfigRepository, JdbcTemplate jdbc,
                             RestClient.Builder restClientBuilder,
                             @Value("${registerwerk.indexer.starknet.repair-occurred-at:true}") boolean enabled) {
        this.chainConfigRepository = chainConfigRepository;
        this.jdbc = jdbc;
        this.restClient = restClientBuilder.build();
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void repairOnStartup() {
        repairAll();
    }

    @SchedulerLock(name = "starknetOccurredAtRepair", lockAtMostFor = "PT10M")
    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    public void repairScheduled() {
        repairAll();
    }

    /** Returns the number of rows corrected across all enabled Starknet chains. */
    int repairAll() {
        if (!enabled) {
            return 0;
        }
        int total = 0;
        try {
            for (ChainConfig chain : chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET)) {
                if (repaired.contains(chain.getId())) {
                    continue;
                }
                total += repair(chain);
            }
        } catch (Exception e) {
            log.warn("Starknet occurred_at repair failed (retried by the scheduled pass): {}", e.getMessage());
        }
        return total;
    }

    int repair(ChainConfig chain) {
        List<Long> blocks = jdbc.queryForList(
                "SELECT DISTINCT block_number FROM token_transfer "
                        + "WHERE chain_config_id = ? AND block_number IS NOT NULL "
                        + "AND (raw_data IS NULL OR raw_data->>'blockTimestamp' IS NULL) "
                        + "ORDER BY block_number LIMIT " + MAX_BLOCKS_PER_PASS,
                Long.class, chain.getId());
        if (blocks.isEmpty()) {
            repaired.add(chain.getId());
            return 0;
        }
        int repairedRows = 0;
        for (Long block : blocks) {
            Long timestamp = fetchBlockTimestamp(chain.getRpcUrl(), block);
            if (timestamp == null) {
                continue; // retried on the next pass
            }
            repairedRows += jdbc.update(
                    "UPDATE token_transfer SET occurred_at = to_timestamp(?), "
                            + "raw_data = COALESCE(raw_data, '{}'::jsonb) "
                            + "|| jsonb_build_object('blockTimestamp', ?::bigint, 'occurredAtRepaired', true) "
                            + "WHERE chain_config_id = ? AND block_number = ? "
                            + "AND (raw_data IS NULL OR raw_data->>'blockTimestamp' IS NULL)",
                    timestamp, timestamp, chain.getId(), block);
        }
        if (repairedRows > 0) {
            log.info("Starknet chain {}: corrected occurred_at of {} row(s) from block timestamps.",
                    chain.getIdentifier(), repairedRows);
        }
        return repairedRows;
    }

    @SuppressWarnings("unchecked")
    private Long fetchBlockTimestamp(String rpcUrl, long blockNumber) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri(rpcUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "starknet_getBlockWithTxHashes",
                            "params", List.of(Map.of("block_number", blockNumber))))
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.get("result") instanceof Map<?, ?> block
                    && block.get("timestamp") instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception e) {
            log.debug("Could not read Starknet block {} header: {}", blockNumber, e.getMessage());
        }
        return null;
    }
}
