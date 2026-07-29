package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.blockchain.api.StarknetFeltUtils;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Polls enabled Starknet chains for {@code Transfer} events emitted by deployed token contracts
 * (Cairo ERC-20 / ERC-3525, both built on the OpenZeppelin Cairo convention of
 * {@code #[key] from, #[key] to, value: u256}) and persists them in {@code token_transfer}.
 *
 * <p>Discovers which contract addresses to watch from {@code AssetDeployment.contractAddress} —
 * populated at submission time for Starknet via the UDC address precomputation in
 * {@code StarknetTokenService}, so no separate on-chain confirmation step is required before an
 * address becomes trackable. Mirrors {@link GraphNodeSyncService}'s block-cursor checkpointing.
 */
@Service
public class StarknetTransferSyncService {

    private static final Logger log = LoggerFactory.getLogger(StarknetTransferSyncService.class);

    /**
     * starknet_keccak("Transfer") — the standard OpenZeppelin Cairo ERC-20/721/3525 Transfer
     * event selector. Stable across every contract built on the OZ Cairo library, so it can be
     * hardcoded rather than derived at runtime.
     */
    static final String TRANSFER_EVENT_SELECTOR =
            "0x99cd8bde557814842a3121e8ddfd433a539b8c9f14bf31ebf108d12e6196e9";

    static final int MAX_CONSECUTIVE_ERRORS = 10;
    static final int CHUNK_SIZE = 200;

    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final ExplorerUrlBuilder explorerUrlBuilder;
    private final RestClient restClient;

    public StarknetTransferSyncService(
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            ExplorerUrlBuilder explorerUrlBuilder,
            RestClient.Builder restClientBuilder) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.explorerUrlBuilder = explorerUrlBuilder;
        this.restClient = restClientBuilder.build();
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    @SchedulerLock(name = "starknetTransferSync", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000)
    public void syncAllStarknetChains() {
        try {
            List<ChainConfig> chains = chainConfigRepository
                    .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET);

            if (chains.isEmpty()) {
                log.debug("No enabled Starknet chains; nothing to sync.");
                return;
            }

            for (ChainConfig chain : chains) {
                try {
                    syncChain(chain);
                } catch (Exception e) {
                    log.error("Unexpected error syncing Starknet chain {}: {}",
                            chain.getIdentifier(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error in Starknet sync scheduler: {}", e.getMessage(), e);
        }
    }

    // ── Per-chain sync ────────────────────────────────────────────────────────

    @Transactional
    public void syncChain(ChainConfig chain) {
        Network network = Network.valueOf(chain.getNetworkType().name());
        List<AssetDeployment> deployments = assetDeploymentRepository.findByChainAndNetwork(Chain.STARKNET, network);

        Map<String, AssetDeployment> byNormalizedAddress = new HashMap<>();
        for (AssetDeployment d : deployments) {
            if (d.getContractAddress() != null && !d.getContractAddress().isBlank()) {
                byNormalizedAddress.put(normalizeFelt(d.getContractAddress()), d);
            }
        }

        if (byNormalizedAddress.isEmpty()) {
            log.debug("No Starknet deployments with a known contract address on chain {}; skipping poll.",
                    chain.getIdentifier());
            return;
        }

        IndexerState state = loadOrCreateState(chain);
        if (state.getStatus() == IndexerState.IndexerStatus.ERROR
                && state.getConsecutiveErrors() >= MAX_CONSECUTIVE_ERRORS) {
            log.warn("Skipping Starknet chain {} — indexer is in ERROR state with {} consecutive errors.",
                    chain.getIdentifier(), state.getConsecutiveErrors());
            return;
        }

        long fromBlock = state.getLastSyncedBlock() != null ? state.getLastSyncedBlock() + 1 : 0L;

        try {
            long headBlock = fetchBlockNumber(chain.getRpcUrl());
            if (fromBlock > headBlock) {
                state.setLastSyncedAt(Instant.now());
                state.setStatus(IndexerState.IndexerStatus.ACTIVE);
                indexerStateRepository.save(state);
                return;
            }

            // Each watched address is an independent RPC round-trip (itself possibly paginated),
            // so fetch them concurrently; only the DB writes below stay on the calling thread,
            // since Hibernate's persistence context isn't safe to share across threads.
            Map<String, CompletableFuture<List<Map<String, Object>>>> eventsByAddress = new HashMap<>();
            for (String address : byNormalizedAddress.keySet()) {
                eventsByAddress.put(address, CompletableFuture.supplyAsync(
                        () -> fetchTransferEvents(chain.getRpcUrl(), address, fromBlock, headBlock)));
            }
            CompletableFuture.allOf(eventsByAddress.values().toArray(CompletableFuture[]::new)).join();

            int totalSaved = 0;
            for (Map.Entry<String, CompletableFuture<List<Map<String, Object>>>> entry : eventsByAddress.entrySet()) {
                Map<String, Integer> logIndexByTx = new HashMap<>();

                for (Map<String, Object> event : entry.getValue().join()) {
                    String txHash = (String) event.get("transaction_hash");
                    if (txHash == null) {
                        continue;
                    }
                    // Starknet's get_events response carries no explicit per-event index, so we
                    // derive a stable position among events sharing the same tx within this
                    // fetch — matches "log index" semantics closely enough for dedup purposes.
                    int logIndex = logIndexByTx.merge(txHash, 0, (oldVal, one) -> oldVal + 1);

                    boolean duplicate = tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(
                            chain.getId(), txHash, logIndex);
                    if (duplicate) {
                        continue;
                    }

                    TokenTransfer transfer = mapToEntity(chain, event, txHash, logIndex,
                            byNormalizedAddress.get(entry.getKey()));
                    if (transfer == null) {
                        continue;
                    }
                    tokenTransferRepository.save(transfer);
                    totalSaved++;
                }
            }

            // Every fetch above used to_block=headBlock, so no event can ever be newer than it —
            // headBlock is always the correct new checkpoint.
            state.setLastSyncedBlock(headBlock);
            state.setLastSyncedAt(Instant.now());
            state.setConsecutiveErrors(0);
            state.setLastError(null);
            state.setStatus(IndexerState.IndexerStatus.ACTIVE);
            indexerStateRepository.save(state);

            if (totalSaved > 0) {
                log.info("Starknet chain {}: synced {} new transfer(s) up to block {}.",
                        chain.getIdentifier(), totalSaved, headBlock);
            } else {
                log.debug("Starknet chain {}: no new transfers found from block {}.",
                        chain.getIdentifier(), fromBlock);
            }
        } catch (Exception e) {
            int errors = state.getConsecutiveErrors() + 1;
            state.setConsecutiveErrors(errors);
            state.setLastError(truncate(e.getMessage(), 2000));
            if (errors >= MAX_CONSECUTIVE_ERRORS) {
                state.setStatus(IndexerState.IndexerStatus.ERROR);
                log.error("Starknet chain {}: indexer set to ERROR after {} consecutive failures. Last error: {}",
                        chain.getIdentifier(), errors, e.getMessage());
            } else {
                log.warn("Starknet chain {}: sync error ({}/{}): {}",
                        chain.getIdentifier(), errors, MAX_CONSECUTIVE_ERRORS, e.getMessage());
            }
            indexerStateRepository.save(state);
        }
    }

    // ── Event decoding ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private TokenTransfer mapToEntity(ChainConfig chain, Map<String, Object> event, String txHash,
            int logIndex, AssetDeployment deployment) {
        List<Object> keys = (List<Object>) event.get("keys");
        List<Object> data = (List<Object>) event.get("data");
        if (keys == null || keys.size() < 3 || data == null || data.size() < 2) {
            log.debug("Skipping Starknet event with unexpected shape: tx={}", txHash);
            return null;
        }

        BigInteger fromFelt = parseFelt((String) keys.get(1));
        BigInteger toFelt = parseFelt((String) keys.get(2));
        BigInteger valueLow = parseFelt((String) data.get(0));
        BigInteger valueHigh = parseFelt((String) data.get(1));
        BigInteger value = valueLow.add(valueHigh.shiftLeft(128));

        TokenTransfer.EventType eventType;
        if (fromFelt.signum() == 0) {
            eventType = TokenTransfer.EventType.MINT;
        } else if (toFelt.signum() == 0) {
            eventType = TokenTransfer.EventType.BURN;
        } else {
            eventType = TokenTransfer.EventType.TRANSFER;
        }

        Object blockNumberObj = event.get("block_number");
        Long blockNumber = blockNumberObj instanceof Number n ? n.longValue() : null;

        TokenTransfer transfer = new TokenTransfer();
        transfer.setChainConfigId(chain.getId());
        transfer.setContractAddress((String) event.get("from_address"));
        transfer.setFromAddress(fromFelt.signum() == 0 ? null : "0x" + fromFelt.toString(16));
        transfer.setToAddress(toFelt.signum() == 0 ? null : "0x" + toFelt.toString(16));
        transfer.setAmount(new java.math.BigDecimal(value));
        transfer.setEventType(eventType);
        transfer.setTxHash(txHash);
        transfer.setBlockNumber(blockNumber);
        transfer.setLogIndex(logIndex);
        transfer.setOccurredAt(Instant.now());
        transfer.setExplorerTxUrl(explorerUrlBuilder.buildTxUrl(chain, txHash));
        if (deployment != null) {
            transfer.setDeploymentId(deployment.getId());
            transfer.setAssetId(deployment.getAssetId());
        }
        transfer.setRawData(Map.of(
                "blockNumber", blockNumber != null ? blockNumber : -1,
                "logIndex", logIndex
        ));
        return transfer;
    }

    // ── RPC helpers ───────────────────────────────────────────────────────────

    private long fetchBlockNumber(String rpcUrl) {
        Map<String, Object> requestBody = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "starknet_blockNumber", "params", List.of());
        Map<String, Object> response = post(rpcUrl, requestBody);
        Object result = response.get("result");
        if (result instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("Unexpected starknet_blockNumber response: " + response);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTransferEvents(
            String rpcUrl, String address, long fromBlock, long toBlock) {
        List<Map<String, Object>> allEvents = new ArrayList<>();
        String continuationToken = null;

        do {
            Map<String, Object> filter = new HashMap<>();
            filter.put("from_block", Map.of("block_number", fromBlock));
            filter.put("to_block", Map.of("block_number", toBlock));
            filter.put("address", address);
            filter.put("keys", List.of(List.of(TRANSFER_EVENT_SELECTOR)));
            filter.put("chunk_size", CHUNK_SIZE);
            if (continuationToken != null) {
                filter.put("continuation_token", continuationToken);
            }

            Map<String, Object> requestBody = Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "starknet_getEvents", "params", List.of(filter));

            Map<String, Object> response = post(rpcUrl, requestBody);
            if (response.containsKey("error")) {
                throw new RuntimeException("starknet_getEvents error: " + response.get("error"));
            }

            Map<String, Object> result = (Map<String, Object>) response.get("result");
            if (result == null) {
                break;
            }
            Object events = result.get("events");
            if (events instanceof List<?> list) {
                allEvents.addAll((List<Map<String, Object>>) list);
            }
            continuationToken = (String) result.get("continuation_token");
        } while (continuationToken != null);

        return allEvents;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String rpcUrl, Map<String, Object> requestBody) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri(rpcUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
            return response != null ? response : Map.of();
        } catch (Exception e) {
            throw new RuntimeException("Starknet RPC call failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IndexerState loadOrCreateState(ChainConfig chain) {
        return indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), IndexerState.IndexerType.STARKNET_POLL)
                .orElseGet(() -> {
                    IndexerState s = new IndexerState();
                    s.setChainConfigId(chain.getId());
                    s.setIndexerType(IndexerState.IndexerType.STARKNET_POLL);
                    s.setStatus(IndexerState.IndexerStatus.ACTIVE);
                    return indexerStateRepository.save(s);
                });
    }

    /**
     * Unlike {@link StarknetFeltUtils#parseHexFelt}, treats null/blank as zero rather than
     * throwing — RPC event payloads can be malformed, and skipping/zeroing is preferable to
     * crashing the whole poll over one bad field.
     */
    private static BigInteger parseFelt(String hex) {
        if (hex == null || hex.isBlank()) {
            return BigInteger.ZERO;
        }
        return StarknetFeltUtils.parseHexFelt(hex);
    }

    /** Canonical, non-zero-padded lowercase-hex form, matching how contractAddress is stored. */
    static String normalizeFelt(String hex) {
        return "0x" + parseFelt(hex).toString(16);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
