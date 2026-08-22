package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.BlockMeta;
import de.makibytes.registerwerk.indexer.internal.GraphNodeClient.GraphTransfer;
import de.makibytes.registerwerk.indexer.internal.ReorgGuard.ProbeOutcome;
import de.makibytes.registerwerk.indexer.internal.ReorgGuard.ProbeResult;
import de.makibytes.registerwerk.indexer.internal.ReorgGuard.VerifyResult;
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
import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scheduled service that polls enabled EVM chains' Graph Node subgraphs for new token
 * transfers and persists them in the {@code token_transfer} table.
 *
 * <p>Sync progress is checkpointed per chain in {@code indexer_state}. Consecutive errors are
 * counted and the indexer is paused with status {@code ERROR} once
 * {@link #MAX_CONSECUTIVE_ERRORS} is reached.
 *
 * <p><strong>Reorg safety:</strong> every tick first reads the Graph Node's current
 * {@code _meta} head. Newly-fetched transfers are written PROVISIONAL or FINAL depending on
 * whether they already clear {@link BlockchainTxProperties#confirmationsFor}; PROVISIONAL rows
 * get a {@code block_hash} recorded via a time-travel {@code _meta(block:{number})} query. After
 * writing the page, {@link ReorgGuard} re-verifies every still-PROVISIONAL block for this chain
 * (not just the ones just written) by re-fetching its current canonical hash and comparing
 * against what was recorded; a mismatch marks everything from the fork point onward ORPHANED
 * (never deleted) and rewinds the cursor so the next tick re-indexes from there.
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
    private final BlockchainTxProperties txProperties;
    private final ReorgGuard reorgGuard;
    private final BlockchainClientRegistry clientRegistry;
    private final RpcNodeRepository rpcNodeRepository;
    private final ChaincacheFinalityProbe chaincacheFinalityProbe;

    public GraphNodeSyncService(
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            GraphNodeClient graphNodeClient,
            ExplorerUrlBuilder explorerUrlBuilder,
            AssetDeploymentRepository assetDeploymentRepository,
            BlockchainTxProperties txProperties,
            ReorgGuard reorgGuard,
            BlockchainClientRegistry clientRegistry,
            RpcNodeRepository rpcNodeRepository,
            ChaincacheFinalityProbe chaincacheFinalityProbe) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.graphNodeClient = graphNodeClient;
        this.explorerUrlBuilder = explorerUrlBuilder;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.txProperties = txProperties;
        this.reorgGuard = reorgGuard;
        this.clientRegistry = clientRegistry;
        this.rpcNodeRepository = rpcNodeRepository;
        this.chaincacheFinalityProbe = chaincacheFinalityProbe;
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    /**
     * Triggered every 30 seconds. Iterates over all enabled EVM chains that have a configured
     * Graph Node and syncs each one.
     */
    @SchedulerLock(name = "graphNodeSync", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 55_000)
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
        String chainName = ReorgGuard.chainNameFrom(chain.getIdentifier());
        int required = txProperties.confirmationsFor(chainName);
        int safeRequired = txProperties.safeConfirmationsFor(chainName);

        // Head + hasIndexingErrors up front. hasIndexingErrors is graph-node's own signal that
        // its view of the subgraph may be stale/wrong (e.g. mid-revert after a reorg it detected
        // itself) — we deliberately do not trust PROVISIONAL/SAFE/FINALIZED classification without
        // a reliable head, so a missing/erroring _meta degrades this tick to "write everything
        // PROVISIONAL, skip the reorg re-verification pass" rather than failing the whole sync.
        Optional<BlockMeta> headMeta = graphNodeClient.fetchMeta(chain, null);
        Long headBlock = null;
        if (headMeta.isPresent() && !headMeta.get().hasIndexingErrors()) {
            headBlock = headMeta.get().blockNumber();
        } else if (headMeta.isPresent()) {
            log.warn("Chain {}: graph-node reports hasIndexingErrors=true; skipping finality "
                    + "classification this tick.", chain.getIdentifier());
        }
        final Long effectiveHead = headBlock;
        // TAG_BASED chains: fetch the node's safe/finalized tags once per chain per tick (not once
        // per transfer/probe — every call below this point shares the same finality snapshot).
        // Other models don't need them: DEPTH_BASED derives finality from effectiveHead +
        // required/safeRequired, INSTANT is always final.
        final Long finalizedBlockNumber = resolveFinalizedBlockNumber(chain, effectiveHead);
        final Long safeBlockNumber = resolveSafeBlockNumber(chain, effectiveHead);

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

        Map<Long, String> hashCache = new HashMap<>();

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

                    TokenTransfer transfer = mapToEntity(chain, gt, deploymentsByAddress,
                            effectiveHead, required, safeRequired, safeBlockNumber, finalizedBlockNumber, hashCache);
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

            // Re-verify the provisional window (every still-PROVISIONAL block for this chain, not
            // just what was just written) before advancing the cursor, so a reorg discovered here
            // can still redirect where the cursor lands this tick.
            Long forkBlock = null;
            if (effectiveHead != null) {
                ReorgGuard.FinalityProbe probe = chaincacheProbeFor(chain)
                        .<ReorgGuard.FinalityProbe>map(node -> blockNumber -> probeChaincacheBlock(chain, node, blockNumber))
                        .orElse(blockNumber -> probeEvmBlock(chain, blockNumber, effectiveHead, required, safeRequired,
                                safeBlockNumber, finalizedBlockNumber, hashCache));
                VerifyResult result = reorgGuard.reverifyUnsettledWindow(chain.getId(), probe);
                if (result.reorgDetected()) {
                    forkBlock = result.forkBlock();
                }
                if (result.promotedSafe() > 0) {
                    log.debug("Chain {}: {} transfer row(s) promoted PROVISIONAL -> SAFE.",
                            chain.getIdentifier(), result.promotedSafe());
                }
                if (result.promotedFinalized() > 0) {
                    log.debug("Chain {}: {} transfer row(s) promoted PROVISIONAL -> FINALIZED.",
                            chain.getIdentifier(), result.promotedFinalized());
                }
            }

            if (forkBlock != null) {
                // Rewind: re-index from the fork point forward. lastFinalBlock is clamped down too
                // if the fork reached at/below it (a deeper-than-policy reorg — already logged
                // CRITICAL by ReorgGuard).
                long rewoundTo = forkBlock - 1;
                state.setLastSyncedBlock(rewoundTo < 0 ? null : rewoundTo);
                if (state.getLastFinalBlock() != null && state.getLastFinalBlock() >= forkBlock) {
                    state.setLastFinalBlock(rewoundTo < 0 ? null : rewoundTo);
                }
                log.warn("Chain {}: cursor rewound to block {} after reorg at fork block {}.",
                        chain.getIdentifier(), rewoundTo, forkBlock);
            } else if (highestBlock >= 0) {
                state.setLastSyncedBlock(highestBlock);
                Long finalThrough = switch (chain.getFinalityModel()) {
                    case INSTANT -> effectiveHead;
                    case TAG_BASED -> finalizedBlockNumber;
                    case DEPTH_BASED -> effectiveHead != null ? effectiveHead - required : null;
                };
                if (finalThrough != null && finalThrough >= 0
                        && (state.getLastFinalBlock() == null || finalThrough > state.getLastFinalBlock())) {
                    state.setLastFinalBlock(Math.min(finalThrough, highestBlock));
                }
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

    // ── Reorg probe ───────────────────────────────────────────────────────────

    /** The enabled {@link RpcNode.NodeKind#CHAINCACHE} node to probe for {@code chain}, when the
     *  chain opted into {@link ChainConfig.FinalitySource#CHAINCACHE} — empty for every other
     *  chain, or if it opted in but currently has no such node (an inconsistent, transient state
     *  {@code RpcNodeService#recomputeFinalitySource} self-heals on the next node change; falling
     *  back to RPC self-probing here rather than failing the tick is the right response to it). */
    private Optional<RpcNode> chaincacheProbeFor(ChainConfig chain) {
        if (chain.getFinalitySource() != ChainConfig.FinalitySource.CHAINCACHE) {
            return Optional.empty();
        }
        return rpcNodeRepository.findByChainConfig_IdAndKindAndEnabledTrue(chain.getId(), RpcNode.NodeKind.CHAINCACHE)
                .stream().findFirst();
    }

    /** {@link ReorgGuard.FinalityProbe} for a {@link ChainConfig.FinalitySource#CHAINCACHE} chain
     *  — reads the block's finality directly from chaincache's own canonical-chain record
     *  ({@link ChaincacheFinalityProbe}) instead of RPC-self-probing it (see
     *  {@link #probeEvmBlock}). Detecting {@link ProbeResult#ORPHANED} by comparing the returned
     *  hash against whatever baseline is already stored is identical to {@code probeEvmBlock}'s
     *  own logic — chaincache reports what it currently has on record; recognizing that it
     *  changed from Registerwerk's own stored baseline is this class's job either way. */
    private ProbeOutcome probeChaincacheBlock(ChainConfig chain, RpcNode chaincacheNode, long blockNumber) {
        Optional<ChaincacheFinalityProbe.Observation> observation =
                chaincacheFinalityProbe.observe(chaincacheNode, blockNumber);
        if (observation.isEmpty()) {
            return ProbeOutcome.unknown();
        }
        String freshHash = observation.get().blockHash();

        List<String> stored = tokenTransferRepository.findDistinctBlockHashesAt(chain.getId(), blockNumber);
        if (!stored.isEmpty() && stored.stream().noneMatch(freshHash::equals)) {
            return new ProbeOutcome(ProbeResult.ORPHANED, freshHash);
        }
        if (stored.isEmpty()) {
            tokenTransferRepository.backfillBlockHashAtBlock(chain.getId(), blockNumber, freshHash);
        }
        return new ProbeOutcome(observation.get().level(), freshHash);
    }

    /** EVM {@link ReorgGuard.FinalityProbe}: compares a freshly re-fetched canonical hash for
     *  {@code blockNumber} against whatever hash was previously recorded for it. */
    private ProbeOutcome probeEvmBlock(ChainConfig chain, long blockNumber, long headBlock, int required,
            int safeRequired, Long safeBlockNumber, Long finalizedBlockNumber, Map<Long, String> hashCache) {
        String freshHash = fetchAndCacheHash(chain, blockNumber, hashCache);
        if (freshHash == null) {
            return ProbeOutcome.unknown();
        }

        List<String> stored = tokenTransferRepository.findDistinctBlockHashesAt(chain.getId(), blockNumber);
        if (!stored.isEmpty() && stored.stream().noneMatch(freshHash::equals)) {
            return new ProbeOutcome(ProbeResult.ORPHANED, freshHash);
        }
        if (stored.isEmpty()) {
            // No baseline was ever recorded for this height (e.g. a transient failure at insert
            // time) — adopt the fresh hash as the baseline now rather than treating "nothing to
            // compare against" as either a match or a mismatch.
            tokenTransferRepository.backfillBlockHashAtBlock(chain.getId(), blockNumber, freshHash);
        }

        FinalityLevel level = EvmUtils.finalityOf(chain.getFinalityModel(), blockNumber, headBlock,
                safeBlockNumber, finalizedBlockNumber, required, safeRequired);
        ProbeResult result = switch (level) {
            case FINALIZED -> ProbeResult.FINALIZED;
            case SAFE -> ProbeResult.SAFE;
            case PROVISIONAL, ORPHANED -> ProbeResult.PROVISIONAL;
        };
        return new ProbeOutcome(result, freshHash);
    }

    /** For {@link ChainConfig.FinalityModel#TAG_BASED} chains only, the node's current
     *  {@code finalized} block tag — fetched once per chain per tick. Null for any other model,
     *  when there's no reliable {@code effectiveHead} this tick, or if the RPC client / tag
     *  lookup fails — rows conservatively stay PROVISIONAL rather than risk misreading an
     *  unsupported/erroring tag as final. */
    private Long resolveFinalizedBlockNumber(ChainConfig chain, Long effectiveHead) {
        if (chain.getFinalityModel() != ChainConfig.FinalityModel.TAG_BASED || effectiveHead == null) {
            return null;
        }
        try {
            Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
            return EvmUtils.finalizedBlockNumber(web3j).orElse(null);
        } catch (Exception e) {
            log.warn("Chain {}: could not resolve finalized block tag this tick ({}); TAG_BASED "
                    + "rows stay PROVISIONAL until it succeeds.", chain.getIdentifier(), e.getMessage());
            return null;
        }
    }

    /** For {@link ChainConfig.FinalityModel#TAG_BASED} chains only, the node's current
     *  {@code safe} block tag — mirrors {@link #resolveFinalizedBlockNumber} exactly. */
    private Long resolveSafeBlockNumber(ChainConfig chain, Long effectiveHead) {
        if (chain.getFinalityModel() != ChainConfig.FinalityModel.TAG_BASED || effectiveHead == null) {
            return null;
        }
        try {
            Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
            return EvmUtils.safeBlockNumber(web3j).orElse(null);
        } catch (Exception e) {
            log.warn("Chain {}: could not resolve safe block tag this tick ({}); TAG_BASED "
                    + "rows stay PROVISIONAL until it succeeds.", chain.getIdentifier(), e.getMessage());
            return null;
        }
    }

    private String fetchAndCacheHash(ChainConfig chain, long blockNumber, Map<Long, String> hashCache) {
        if (hashCache.containsKey(blockNumber)) {
            return hashCache.get(blockNumber);
        }
        String hash = graphNodeClient.fetchMeta(chain, blockNumber)
                .filter(m -> !m.hasIndexingErrors())
                .map(BlockMeta::blockHash)
                .orElse(null);
        hashCache.put(blockNumber, hash);
        return hash;
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
            Map<String, de.makibytes.registerwerk.deployment.api.AssetDeployment> deploymentsByAddress,
            Long headBlock, int required, int safeRequired, Long safeBlockNumber, Long finalizedBlockNumber,
            Map<Long, String> hashCache) {
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

        // Finality classification. No head available this tick -> conservatively
        // PROVISIONAL with no hash (the next tick's reorg pass, once the head is available again,
        // will fetch a hash and either promote it or leave it PROVISIONAL as appropriate).
        if (headBlock == null) {
            t.setFinalityStatus(FinalityLevel.PROVISIONAL);
        } else {
            FinalityLevel level = EvmUtils.finalityOf(chain.getFinalityModel(), gt.blockNumber(), headBlock,
                    safeBlockNumber, finalizedBlockNumber, required, safeRequired);
            t.setFinalityStatus(level);
            if (level == FinalityLevel.PROVISIONAL) {
                t.setBlockHash(fetchAndCacheHash(chain, gt.blockNumber(), hashCache));
            }
        }

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
