package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.blockchain.api.StellarUtils;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls enabled Stellar chains' Horizon {@code /accounts/{issuer}/operations} feed (cursor-based) for
 * operations touching a tracked asset's issuing account, and persists them in {@code token_transfer}.
 *
 * <p>Discovers which issuer accounts to watch from {@code AssetDeployment.contractAddress} -
 * populated at submission time for Stellar with the issuing account's G-address (see
 * {@code StellarAssetService.createStellarAsset}). The asset code itself is not persisted
 * anywhere; it is re-derived deterministically from the asset UUID via the shared
 * {@link StellarUtils#deriveAssetCode}.
 *
 * <p>Stage 1 (P4-08): every deployment owns its cursor ({@code indexer_deployment_cursor}), so a
 * deployment added later is scanned from its own beginning and one failing issuer neither advances
 * nor blocks the others. The full operation feed (not only {@code /payments}) is read so that
 * {@code clawback} operations - the registrar's forced-transfer instrument on Stellar, see
 * {@code StellarAssetService.clawbackAsset} - are booked as BURN rows.
 *
 * <p><b>Known limitation (Stage 2 pending):</b> only operations touching the issuing account are
 * observable. Holder-to-holder payments, DEX fills, liquidity-pool and claimable-balance movements
 * never appear in that feed, so the register cannot be reconciled from indexed history alone; the
 * coverage guard therefore keeps Stellar deployments {@code NOT_INDEXED} until a balance-authoritative
 * check (Horizon {@code /accounts?asset=CODE:ISSUER}) lands. Horizon has no {@code effects?asset=}
 * filter, so movements cannot be replayed per asset. {@code clawback_claimable_balance} carries no
 * amount and is not booked either.
 */
@Service
public class StellarTransferSyncService {

    private static final Logger log = LoggerFactory.getLogger(StellarTransferSyncService.class);

    static final int MAX_CONSECUTIVE_ERRORS = 10;
    static final int PAGE_LIMIT = 200;

    /**
     * bounds the per-issuer-account fan-out below — same reasoning as
     * {@code StarknetTransferSyncService.FAN_OUT_BULKHEAD_CONFIG}: a waiting (not fail-fast)
     * bulkhead, so that a saturated queue delays an issuer's fetch (its cursor simply
     * does not advance) instead of silently skipping it.
     */
    private static final BulkheadConfig FAN_OUT_BULKHEAD_CONFIG = BulkheadConfig.custom()
            .maxConcurrentCalls(8)
            .maxWaitDuration(Duration.ofSeconds(25))
            .build();

    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final IndexerDeploymentCursorRepository cursorRepository;
    private final IndexerSyncSupport syncSupport;
    private final ExplorerUrlBuilder explorerUrlBuilder;
    private final RestClient restClient;
    private final Bulkhead fanOutBulkhead;

    public StellarTransferSyncService(
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            IndexerDeploymentCursorRepository cursorRepository,
            IndexerSyncSupport syncSupport,
            ExplorerUrlBuilder explorerUrlBuilder,
            RestClient.Builder restClientBuilder,
            BulkheadRegistry bulkheadRegistry) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.cursorRepository = cursorRepository;
        this.syncSupport = syncSupport;
        this.explorerUrlBuilder = explorerUrlBuilder;
        this.restClient = restClientBuilder.build();
        this.fanOutBulkhead = bulkheadRegistry.bulkhead("stellar-transfer-fanout", FAN_OUT_BULKHEAD_CONFIG);
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    @SchedulerLock(name = "stellarTransferSync", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 75_000)
    public void syncAllStellarChains() {
        try {
            List<ChainConfig> chains = chainConfigRepository
                    .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STELLAR);

            if (chains.isEmpty()) {
                log.debug("No enabled Stellar chains; nothing to sync.");
                return;
            }

            for (ChainConfig chain : chains) {
                try {
                    syncChain(chain);
                } catch (Exception e) {
                    log.error("Unexpected error syncing Stellar chain {}: {}",
                            chain.getIdentifier(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error in Stellar sync scheduler: {}", e.getMessage(), e);
        }
    }

    // ── Per-chain sync ────────────────────────────────────────────────────────

    /** One deployment's fetch, in flight - {@code future} resolves independently of the others. */
    private record AccountFetch(AssetDeployment deployment, String issuerAccount, String assetCode,
            String cursor, CompletableFuture<List<Map<String, Object>>> future) {}

    /**
     * One pass for {@code chain}. Issuer feeds are fetched concurrently (from each deployment's own
     * cursor); every deployment is then persisted in its own transaction together with its cursor, so a
     * failing issuer leaves its own cursor untouched and does not roll back the others. Any failure is
     * counted in the chain's {@code STELLAR_HORIZON} state in a separate transaction.
     */
    public void syncChain(ChainConfig chain) {
        Network network = Network.valueOf(chain.getNetworkType().name());
        List<AssetDeployment> deployments = assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, network)
                .stream()
                .filter(d -> d.getContractAddress() != null && !d.getContractAddress().isBlank())
                .filter(d -> d.getDeploymentStatus() != AssetDeployment.DeploymentStatus.FAILED)
                .toList();

        if (deployments.isEmpty()) {
            log.debug("No Stellar deployments with a known issuer account on chain {}; skipping poll.",
                    chain.getIdentifier());
            return;
        }

        IndexerState state = indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), IndexerState.IndexerType.STELLAR_HORIZON)
                .orElse(null);
        if (state != null && state.getStatus() == IndexerState.IndexerStatus.ERROR
                && state.getConsecutiveErrors() >= MAX_CONSECUTIVE_ERRORS) {
            log.warn("Skipping Stellar chain {} - indexer is in ERROR state with {} consecutive errors.",
                    chain.getIdentifier(), state.getConsecutiveErrors());
            return;
        }

        // Each watched issuer account is an independent, self-paginating Horizon call, so fetch them
        // concurrently; only the DB writes stay on the calling thread.
        List<AccountFetch> fetches = deployments.stream()
                .map(d -> {
                    String cursor = cursorRepository
                            .findByDeploymentIdAndIndexerType(d.getId(), IndexerState.IndexerType.STELLAR_HORIZON)
                            .map(IndexerDeploymentCursor::getCursorValue)
                            .filter(c -> !c.isBlank())
                            .orElse("0");
                    return new AccountFetch(d, d.getContractAddress(), StellarUtils.deriveAssetCode(d.getAssetId()), cursor,
                            CompletableFuture.supplyAsync(() -> fanOutBulkhead.executeSupplier(
                                    () -> fetchOperations(chain.getRpcUrl(), d.getContractAddress(), cursor))));
                })
                .toList();
        CompletableFuture.allOf(fetches.stream().map(AccountFetch::future).toArray(CompletableFuture[]::new))
                .handle((ok, err) -> null).join();

        Exception firstFailure = null;
        int totalSaved = 0;
        for (AccountFetch fetch : fetches) {
            try {
                List<Map<String, Object>> operations = fetch.future().join();
                totalSaved += persistDeployment(chain, fetch, operations);
            } catch (Exception e) {
                log.warn("Stellar chain {}: deployment {} not synced this pass: {}", chain.getIdentifier(),
                        fetch.deployment().getId(), e.getMessage());
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }

        if (firstFailure != null) {
            syncSupport.recordFailure(chain.getId(), chain.getIdentifier(),
                    IndexerState.IndexerType.STELLAR_HORIZON, firstFailure, MAX_CONSECUTIVE_ERRORS);
            return;
        }
        syncSupport.inTransaction(() -> {
            IndexerState s = loadOrCreateState(chain);
            s.setLastSyncedAt(Instant.now());
            s.setConsecutiveErrors(0);
            s.setLastError(null);
            s.setStatus(IndexerState.IndexerStatus.ACTIVE);
            indexerStateRepository.save(s);
        });
        if (totalSaved > 0) {
            log.info("Stellar chain {}: synced {} new transfer(s).", chain.getIdentifier(), totalSaved);
        } else {
            log.debug("Stellar chain {}: no new transfers found.", chain.getIdentifier());
        }
    }

    private int persistDeployment(ChainConfig chain, AccountFetch fetch, List<Map<String, Object>> operations) {
        // Horizon returns operations in ascending cursor order: the last record is this feed's high-water mark.
        String highWaterMark = operations.isEmpty() ? fetch.cursor()
                : (String) operations.get(operations.size() - 1).get("paging_token");
        AtomicInteger saved = new AtomicInteger();
        syncSupport.inTransaction(() -> {
            for (Map<String, Object> operation : operations) {
                if (!matchesTrackedAsset(operation, fetch.assetCode(), fetch.issuerAccount())) {
                    continue;
                }
                String txHash = (String) operation.get("transaction_hash");
                if (txHash == null) {
                    continue;
                }
                StellarOperationId opId = StellarOperationId.parse(operation.get("id"));
                Integer logIndex = opId != null ? opId.logIndex() : null;
                if (tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndexAndContractAddress(
                        chain.getId(), txHash, logIndex, fetch.issuerAccount())) {
                    continue;
                }
                tokenTransferRepository.save(mapToEntity(chain, operation, fetch.deployment(), fetch.issuerAccount(),
                        txHash, logIndex, opId != null ? opId.ledger() : null));
                saved.incrementAndGet();
            }
            IndexerDeploymentCursor cursor = cursorRepository
                    .findByDeploymentIdAndIndexerType(fetch.deployment().getId(), IndexerState.IndexerType.STELLAR_HORIZON)
                    .orElseGet(() -> {
                        IndexerDeploymentCursor c = new IndexerDeploymentCursor();
                        c.setDeploymentId(fetch.deployment().getId());
                        c.setIndexerType(IndexerState.IndexerType.STELLAR_HORIZON);
                        return c;
                    });
            cursor.setCursorValue(highWaterMark);
            cursor.setLastSyncedAt(Instant.now());
            cursorRepository.save(cursor);
        });
        return saved.get();
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    /** payment / path payments that deliver the tracked asset, and clawbacks of it. */
    private boolean matchesTrackedAsset(Map<String, Object> operation, String assetCode, String issuerAccount) {
        Object type = operation.get("type");
        if (!"payment".equals(type) && !"path_payment_strict_receive".equals(type)
                && !"path_payment_strict_send".equals(type) && !"clawback".equals(type)) {
            return false;
        }
        String assetType = (String) operation.get("asset_type");
        if (assetType == null || "native".equals(assetType)) {
            return false;
        }
        return assetCode.equals(operation.get("asset_code")) && issuerAccount.equals(operation.get("asset_issuer"));
    }

    private TokenTransfer mapToEntity(ChainConfig chain, Map<String, Object> payment, AssetDeployment deployment,
            String issuerAccount, String txHash, Integer logIndex, Long ledger) {
        String from = (String) payment.get("from");
        String to = (String) payment.get("to");
        String amountStr = (String) payment.get("amount");
        boolean clawback = "clawback".equals(payment.get("type"));

        TokenTransfer.EventType eventType;
        if (clawback) {
            // The issuer takes the units back from `from`: the holder's balance falls, supply falls.
            eventType = TokenTransfer.EventType.BURN;
            to = null;
        } else if (issuerAccount.equals(from)) {
            eventType = TokenTransfer.EventType.MINT;
        } else if (issuerAccount.equals(to)) {
            eventType = TokenTransfer.EventType.BURN;
        } else {
            eventType = TokenTransfer.EventType.TRANSFER;
        }

        TokenTransfer transfer = new TokenTransfer();
        transfer.setChainConfigId(chain.getId());
        transfer.setContractAddress(issuerAccount);
        transfer.setFromAddress(eventType == TokenTransfer.EventType.MINT ? null : from);
        transfer.setToAddress(eventType == TokenTransfer.EventType.BURN ? null : to);
        transfer.setEventType(eventType);
        transfer.setTxHash(txHash);
        transfer.setBlockNumber(ledger);
        transfer.setLogIndex(logIndex);
        transfer.setOccurredAt(resolveOccurredAt(payment));
        transfer.setExplorerTxUrl(explorerUrlBuilder.buildTxUrl(chain, txHash));
        transfer.setDeploymentId(deployment.getId());
        transfer.setAssetId(deployment.getAssetId());
        if (amountStr != null) {
            try {
                transfer.setAmount(new BigDecimal(amountStr));
            } catch (NumberFormatException ignored) {
                // leave amount null rather than fail the whole sync over one malformed field
            }
        }
        transfer.setRawData(Map.of(
                "pagingToken", String.valueOf(payment.get("paging_token")),
                "type", String.valueOf(payment.get("type"))
        ));
        // Stellar Consensus Protocol has no probabilistic finality: Horizon only returns operations of
        // ledgers that already closed, so every row is FINALIZED on write.
        transfer.setFinalityStatus(FinalityLevel.FINALIZED);
        return transfer;
    }

    /** Uses Horizon's own {@code created_at} (real ledger-close time) when available rather
     *  than the processing time. Falls back to processing time only if it's missing or malformed. */
    private Instant resolveOccurredAt(Map<String, Object> payment) {
        Object createdAt = payment.get("created_at");
        if (createdAt instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (Exception ignored) {
                // fall through to processing-time fallback below
            }
        }
        return Instant.now();
    }

    /**
     * Stellar operation IDs (and paging tokens for payments) are stellar-core "total order IDs":
     * {@code (ledger_sequence << 32) | (tx_application_order << 12) | operation_index}. The low
     * 12 bits are the operation's 0-based index within its transaction (the Stellar equivalent of
     * an EVM log index); the high bits are the ledger sequence. Parsed once and reused for both.
     */
    private record StellarOperationId(int logIndex, long ledger) {
        static StellarOperationId parse(Object id) {
            if (id == null) {
                return null;
            }
            try {
                long totalOrderId = Long.parseLong(id.toString());
                return new StellarOperationId((int) (totalOrderId & 0xFFF), totalOrderId >>> 32);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // ── Horizon REST helpers ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchOperations(String horizonUrl, String account, String cursor) {
        List<Map<String, Object>> all = new ArrayList<>();
        String nextCursor = cursor;

        while (true) {
            Map<String, Object> response = restClient.get()
                    .uri(horizonUrl + "/accounts/{account}/operations?cursor={cursor}&order=asc&limit={limit}&include_failed=false",
                            account, nextCursor, PAGE_LIMIT)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                break;
            }
            Map<String, Object> embedded = (Map<String, Object>) response.get("_embedded");
            List<Map<String, Object>> records = embedded != null
                    ? (List<Map<String, Object>>) embedded.get("records")
                    : List.of();
            if (records == null || records.isEmpty()) {
                break;
            }

            all.addAll(records);
            String lastPagingToken = (String) records.get(records.size() - 1).get("paging_token");

            // A partial page (fewer records than requested) means we've drained the account's
            // available payments up to Horizon's current ledger close.
            if (records.size() < PAGE_LIMIT || lastPagingToken == null || lastPagingToken.equals(nextCursor)) {
                break;
            }
            nextCursor = lastPagingToken;
        }

        return all;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IndexerState loadOrCreateState(ChainConfig chain) {
        return indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), IndexerState.IndexerType.STELLAR_HORIZON)
                .orElseGet(() -> {
                    IndexerState s = new IndexerState();
                    s.setChainConfigId(chain.getId());
                    s.setIndexerType(IndexerState.IndexerType.STELLAR_HORIZON);
                    s.setStatus(IndexerState.IndexerStatus.ACTIVE);
                    return indexerStateRepository.save(s);
                });
    }
}
