package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.GraphTransfer;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Scheduled service that polls enabled EVM chains' Graph Node subgraphs for new token
 * transfers and persists them in the {@code token_transfer} table.
 *
 * <p>Sync progress is checkpointed per chain in {@code indexer_state}. Consecutive errors are
 * counted and the indexer is paused with status {@code ERROR} once
 * {@link #MAX_CONSECUTIVE_ERRORS} is reached.
 */
@Service
public class GraphNodeSyncService {

    private static final Logger log = LoggerFactory.getLogger(GraphNodeSyncService.class);

    static final int PAGE_SIZE = 1_000;
    static final int MAX_CONSECUTIVE_ERRORS = 10;
    static final Duration STALE_THRESHOLD = Duration.ofHours(2);

    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final GraphNodeClient graphNodeClient;
    private final ExplorerUrlBuilder explorerUrlBuilder;
    private final AssetDeploymentRepository assetDeploymentRepository;

    public GraphNodeSyncService(
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            GraphNodeClient graphNodeClient,
            ExplorerUrlBuilder explorerUrlBuilder,
            AssetDeploymentRepository assetDeploymentRepository) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.graphNodeClient = graphNodeClient;
        this.explorerUrlBuilder = explorerUrlBuilder;
        this.assetDeploymentRepository = assetDeploymentRepository;
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    /**
     * Triggered every 30 seconds. Iterates over all enabled EVM chains that have a configured
     * Graph Node and syncs each one.
     */
    @SchedulerLock(name = "graphNodeSync", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000)
    public void syncAllEvmChains() {
        try {
            List<ChainConfig> evmChains = chainConfigRepository
                    .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.EVM)
                    .stream()
                    .filter(c -> c.getGraphNodeUrl() != null && !c.getGraphNodeUrl().isBlank())
                    .toList();

            if (evmChains.isEmpty()) {
                log.debug("No enabled EVM chains with Graph Node configured; nothing to sync.");
                return;
            }

            log.debug("Starting Graph Node sync for {} EVM chain(s).", evmChains.size());
            for (ChainConfig chain : evmChains) {
                try {
                    syncChain(chain);
                } catch (Exception e) {
                    // Per-chain exceptions are isolated so one bad chain never stops the others.
                    log.error("Unexpected error syncing chain {}: {}", chain.getIdentifier(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error in syncAllEvmChains scheduler: {}", e.getMessage(), e);
        }
    }

    // ── Per-chain sync ────────────────────────────────────────────────────────

    @Transactional
    public void syncChain(ChainConfig chain) {
        IndexerState state = loadOrCreateState(chain);

        if (state.getStatus() == IndexerState.IndexerStatus.ERROR
                && state.getConsecutiveErrors() >= MAX_CONSECUTIVE_ERRORS) {
            log.warn("Skipping chain {} — indexer is in ERROR state with {} consecutive errors. "
                            + "Resolve the underlying issue and reset the indexer manually.",
                    chain.getIdentifier(), state.getConsecutiveErrors());
            return;
        }

        long fromBlock = state.getLastSyncedBlock() != null ? state.getLastSyncedBlock() + 1 : 0L;

        // Load all deployments once per sync call (not once per transfer, which would be an
        // O(page_size × total_deployments) full-table scan per PAGE_SIZE=1000 batch), keyed by
        // lowercased contract address to match the existing case-insensitive lookup.
        Map<String, de.makibytes.registerwerk.deployment.api.AssetDeployment> deploymentsByAddress =
                assetDeploymentRepository.findAll().stream()
                        .filter(d -> d.getContractAddress() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                d -> d.getContractAddress().toLowerCase(java.util.Locale.ROOT),
                                d -> d,
                                (first, second) -> first));

        try {
            int skip = 0;
            int totalSaved = 0;
            long highestBlock = fromBlock == 0 ? -1 : fromBlock - 1;

            while (true) {
                List<GraphTransfer> page = graphNodeClient.fetchTransfers(chain, fromBlock, PAGE_SIZE, skip);
                if (page.isEmpty()) {
                    break;
                }

                for (GraphTransfer gt : page) {
                    boolean duplicate = tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(
                            chain.getId(), gt.transactionHash(), (int) gt.logIndex());
                    if (duplicate) {
                        continue;
                    }

                    TokenTransfer transfer = mapToEntity(chain, gt, deploymentsByAddress);
                    tokenTransferRepository.save(transfer);
                    totalSaved++;

                    if (gt.blockNumber() > highestBlock) {
                        highestBlock = gt.blockNumber();
                    }
                }

                skip += page.size();

                // If the page was smaller than PAGE_SIZE, we've reached the end.
                if (page.size() < PAGE_SIZE) {
                    break;
                }
            }

            if (highestBlock >= 0) {
                state.setLastSyncedBlock(highestBlock);
            }
            state.setLastSyncedAt(Instant.now());
            state.setConsecutiveErrors(0);
            state.setLastError(null);
            state.setStatus(IndexerState.IndexerStatus.ACTIVE);
            indexerStateRepository.save(state);

            if (totalSaved > 0) {
                log.info("Chain {}: synced {} new transfers up to block {}.",
                        chain.getIdentifier(), totalSaved, highestBlock);
            } else {
                log.debug("Chain {}: no new transfers found from block {}.", chain.getIdentifier(), fromBlock);
            }

        } catch (Exception e) {
            int errors = state.getConsecutiveErrors() + 1;
            state.setConsecutiveErrors(errors);
            state.setLastError(truncate(e.getMessage(), 2000));
            if (errors >= MAX_CONSECUTIVE_ERRORS) {
                state.setStatus(IndexerState.IndexerStatus.ERROR);
                log.error("Chain {}: indexer set to ERROR after {} consecutive failures. Last error: {}",
                        chain.getIdentifier(), errors, e.getMessage());
            } else {
                log.warn("Chain {}: sync error ({}/{}): {}",
                        chain.getIdentifier(), errors, MAX_CONSECUTIVE_ERRORS, e.getMessage());
            }
            indexerStateRepository.save(state);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IndexerState loadOrCreateState(ChainConfig chain) {
        return indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), IndexerState.IndexerType.GRAPH_NODE)
                .orElseGet(() -> {
                    IndexerState s = new IndexerState();
                    s.setChainConfigId(chain.getId());
                    s.setIndexerType(IndexerState.IndexerType.GRAPH_NODE);
                    s.setStatus(IndexerState.IndexerStatus.ACTIVE);
                    return indexerStateRepository.save(s);
                });
    }

    private TokenTransfer mapToEntity(ChainConfig chain, GraphTransfer gt,
            Map<String, de.makibytes.registerwerk.deployment.api.AssetDeployment> deploymentsByAddress) {
        TokenTransfer t = new TokenTransfer();
        t.setChainConfigId(chain.getId());
        t.setContractAddress(gt.tokenAddress() != null ? gt.tokenAddress() : "");
        t.setFromAddress(gt.from());
        t.setToAddress(gt.to());
        t.setTxHash(gt.transactionHash());
        t.setBlockNumber(gt.blockNumber());
        t.setLogIndex((int) gt.logIndex());
        t.setOccurredAt(Instant.ofEpochSecond(gt.blockTimestamp()));
        t.setEventType(resolveEventType(gt.eventType(), gt.from(), gt.to()));
        t.setExplorerTxUrl(explorerUrlBuilder.buildTxUrl(chain, gt.transactionHash()));

        if (gt.tokenId() != null && !gt.tokenId().isBlank()) {
            try { t.setTokenId(new BigDecimal(gt.tokenId())); } catch (NumberFormatException ignored) {}
        }
        if (gt.amount() != null && !gt.amount().isBlank()) {
            try { t.setAmount(new BigDecimal(gt.amount())); } catch (NumberFormatException ignored) {}
        }

        // Link to deployment if we can find a matching contract address.
        if (gt.tokenAddress() != null) {
            de.makibytes.registerwerk.deployment.api.AssetDeployment d =
                    deploymentsByAddress.get(gt.tokenAddress().toLowerCase(java.util.Locale.ROOT));
            if (d != null) {
                t.setDeploymentId(d.getId());
                t.setAssetId(d.getAssetId());
            }
        }

        t.setRawData(Map.of(
                "graphId", gt.id(),
                "blockNumber", gt.blockNumber(),
                "logIndex", gt.logIndex()
        ));

        return t;
    }

    private TokenTransfer.EventType resolveEventType(String raw, String from, String to) {
        if (raw != null) {
            try {
                return TokenTransfer.EventType.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        // Infer from addresses when the subgraph does not carry an explicit eventType.
        if (from == null || from.isBlank() || from.equals("0x0000000000000000000000000000000000000000")) {
            return TokenTransfer.EventType.MINT;
        }
        if (to == null || to.isBlank() || to.equals("0x0000000000000000000000000000000000000000")) {
            return TokenTransfer.EventType.BURN;
        }
        return TokenTransfer.EventType.TRANSFER;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
