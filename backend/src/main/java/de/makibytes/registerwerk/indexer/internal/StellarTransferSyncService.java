package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.blockchain.api.StellarUtils;
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

/**
 * Polls enabled Stellar chains' Horizon {@code /payments} endpoint (cursor-based, per the
 * mechanism the README always prescribed for this chain) for operations touching a tracked
 * asset's issuing account, and persists them in {@code token_transfer}.
 *
 * <p>Discovers which issuer accounts to watch from {@code AssetDeployment.contractAddress} —
 * populated at submission time for Stellar with the issuing account's G-address (see
 * {@code StellarAssetService.createStellarAsset}). The asset code itself is not persisted
 * anywhere; it is re-derived deterministically from the asset UUID via the shared
 * {@link StellarUtils#deriveAssetCode}, so no schema change was needed to carry it.
 *
 * <p><b>Known limitation:</b> only payments touching the issuing account are tracked (issuance,
 * redemption, and any transfer routed through it) — pure holder-to-holder secondary transfers
 * that never touch the issuer are not yet indexed. This mirrors the honesty of Solana's
 * WebSocket-stub/polling-active state: a real, working mechanism with a documented gap, not a
 * silent one.
 */
@Service
public class StellarTransferSyncService {

    private static final Logger log = LoggerFactory.getLogger(StellarTransferSyncService.class);

    static final int MAX_CONSECUTIVE_ERRORS = 10;
    static final int PAGE_LIMIT = 200;

    /**
     * Phase 3: bounds the per-issuer-account fan-out below — same reasoning as
     * {@code StarknetTransferSyncService.FAN_OUT_BULKHEAD_CONFIG}: a waiting (not fail-fast)
     * bulkhead, because Stellar's cursor is shared across every watched account on the chain
     * (see {@code sharedCursor} below) and advances once regardless of which individual accounts'
     * fetches completed — silently skipping one would lose its transfers in this tick's range,
     * not merely delay them.
     */
    private static final BulkheadConfig FAN_OUT_BULKHEAD_CONFIG = BulkheadConfig.custom()
            .maxConcurrentCalls(8)
            .maxWaitDuration(Duration.ofSeconds(25))
            .build();

    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final ExplorerUrlBuilder explorerUrlBuilder;
    private final RestClient restClient;
    private final Bulkhead fanOutBulkhead;

    public StellarTransferSyncService(
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            ExplorerUrlBuilder explorerUrlBuilder,
            RestClient.Builder restClientBuilder,
            BulkheadRegistry bulkheadRegistry) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
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

    /** One deployment's fetch, in flight — {@code future} resolves independently of the others. */
    private record AccountFetch(AssetDeployment deployment, String issuerAccount, String assetCode,
            CompletableFuture<List<Map<String, Object>>> future) {}

    @Transactional
    public void syncChain(ChainConfig chain) {
        Network network = Network.valueOf(chain.getNetworkType().name());
        List<AssetDeployment> deployments = assetDeploymentRepository.findByChainAndNetwork(Chain.STELLAR, network)
                .stream()
                .filter(d -> d.getContractAddress() != null && !d.getContractAddress().isBlank())
                .toList();

        if (deployments.isEmpty()) {
            log.debug("No Stellar deployments with a known issuer account on chain {}; skipping poll.",
                    chain.getIdentifier());
            return;
        }

        IndexerState state = loadOrCreateState(chain);
        if (state.getStatus() == IndexerState.IndexerStatus.ERROR
                && state.getConsecutiveErrors() >= MAX_CONSECUTIVE_ERRORS) {
            log.warn("Skipping Stellar chain {} — indexer is in ERROR state with {} consecutive errors.",
                    chain.getIdentifier(), state.getConsecutiveErrors());
            return;
        }

        String sharedCursor = state.getLastSyncedSignature() != null ? state.getLastSyncedSignature() : "0";

        try {
            // Each watched issuer account is an independent, self-paginating Horizon call, so
            // fetch them concurrently; only the DB writes below stay on the calling thread,
            // since Hibernate's persistence context isn't safe to share across threads.
            List<AccountFetch> fetches = deployments.stream()
                    .map(d -> new AccountFetch(d, d.getContractAddress(), StellarUtils.deriveAssetCode(d.getAssetId()),
                            CompletableFuture.supplyAsync(() -> fanOutBulkhead.executeSupplier(
                                    () -> fetchPayments(chain.getRpcUrl(), d.getContractAddress(), sharedCursor)))))
                    .toList();
            CompletableFuture.allOf(fetches.stream().map(AccountFetch::future).toArray(CompletableFuture[]::new)).join();

            int totalSaved = 0;
            // One indexer_state row is shared across every watched account on this chain, so we
            // advance the shared cursor to the MINIMUM high-water mark reached across all of
            // them this poll — never skips an account's unprocessed payments, at the cost of
            // occasionally re-scanning (harmlessly, thanks to dedup) an account that ran ahead.
            String newSharedCursor = null;

            for (AccountFetch fetch : fetches) {
                List<Map<String, Object>> payments = fetch.future().join();

                // Horizon returns payments in ascending cursor order, so the last record (if any)
                // carries this account's high-water mark for this poll.
                String lastPagingToken = payments.isEmpty() ? null
                        : (String) payments.get(payments.size() - 1).get("paging_token");
                String accountHighWaterMark = lastPagingToken != null ? lastPagingToken : sharedCursor;

                for (Map<String, Object> payment : payments) {
                    if (!matchesTrackedAsset(payment, fetch.assetCode(), fetch.issuerAccount())) {
                        continue;
                    }

                    String txHash = (String) payment.get("transaction_hash");
                    if (txHash == null) {
                        continue;
                    }
                    StellarOperationId opId = StellarOperationId.parse(payment.get("id"));
                    Integer logIndex = opId != null ? opId.logIndex() : null;

                    boolean duplicate = tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(
                            chain.getId(), txHash, logIndex);
                    if (duplicate) {
                        continue;
                    }

                    TokenTransfer transfer = mapToEntity(chain, payment, fetch.deployment(), fetch.issuerAccount(),
                            txHash, logIndex, opId != null ? opId.ledger() : null);
                    tokenTransferRepository.save(transfer);
                    totalSaved++;
                }

                newSharedCursor = newSharedCursor == null
                        ? accountHighWaterMark
                        : minCursor(newSharedCursor, accountHighWaterMark);
            }

            state.setLastSyncedSignature(newSharedCursor != null ? newSharedCursor : sharedCursor);
            state.setLastSyncedAt(Instant.now());
            state.setConsecutiveErrors(0);
            state.setLastError(null);
            state.setStatus(IndexerState.IndexerStatus.ACTIVE);
            indexerStateRepository.save(state);

            if (totalSaved > 0) {
                log.info("Stellar chain {}: synced {} new transfer(s).", chain.getIdentifier(), totalSaved);
            } else {
                log.debug("Stellar chain {}: no new transfers found.", chain.getIdentifier());
            }
        } catch (Exception e) {
            int errors = state.getConsecutiveErrors() + 1;
            state.setConsecutiveErrors(errors);
            state.setLastError(truncate(e.getMessage(), 2000));
            if (errors >= MAX_CONSECUTIVE_ERRORS) {
                state.setStatus(IndexerState.IndexerStatus.ERROR);
                log.error("Stellar chain {}: indexer set to ERROR after {} consecutive failures. Last error: {}",
                        chain.getIdentifier(), errors, e.getMessage());
            } else {
                log.warn("Stellar chain {}: sync error ({}/{}): {}",
                        chain.getIdentifier(), errors, MAX_CONSECUTIVE_ERRORS, e.getMessage());
            }
            indexerStateRepository.save(state);
        }
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    private boolean matchesTrackedAsset(Map<String, Object> payment, String assetCode, String issuerAccount) {
        Object type = payment.get("type");
        if (!"payment".equals(type) && !"path_payment_strict_receive".equals(type)
                && !"path_payment_strict_send".equals(type)) {
            return false;
        }
        String assetType = (String) payment.get("asset_type");
        if (assetType == null || "native".equals(assetType)) {
            return false;
        }
        return assetCode.equals(payment.get("asset_code")) && issuerAccount.equals(payment.get("asset_issuer"));
    }

    private TokenTransfer mapToEntity(ChainConfig chain, Map<String, Object> payment, AssetDeployment deployment,
            String issuerAccount, String txHash, Integer logIndex, Long ledger) {
        String from = (String) payment.get("from");
        String to = (String) payment.get("to");
        String amountStr = (String) payment.get("amount");

        TokenTransfer.EventType eventType;
        if (issuerAccount.equals(from)) {
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
        // Phase 2: Stellar Consensus Protocol has no probabilistic finality — a ledger either
        // closes with 2/3+ validator quorum agreement or it does not close at all, and Horizon's
        // /payments endpoint only ever returns operations from ledgers that have already closed.
        // There is no equivalent of an EVM/Starknet "provisional, might still be reorged" state
        // to represent here, so every row is FINAL on write (matches the entity default; set
        // explicitly for clarity/documentation).
        transfer.setFinalityStatus(TokenTransfer.FinalityStatus.FINAL);
        return transfer;
    }

    /** Uses Horizon's own {@code created_at} (real ledger-close time) when available rather
     *  than the processing time — every Horizon payment/operation record includes it. Falls
     *  back to processing time only if it's ever missing or malformed. */
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

    /** Lexicographic comparison is valid here because both are numeric stellar-core total order IDs. */
    private String minCursor(String a, String b) {
        if (a.length() != b.length()) {
            return a.length() < b.length() ? a : b;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }

    // ── Horizon REST helpers ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPayments(String horizonUrl, String account, String cursor) {
        List<Map<String, Object>> all = new ArrayList<>();
        String nextCursor = cursor;

        while (true) {
            Map<String, Object> response = restClient.get()
                    .uri(horizonUrl + "/accounts/{account}/payments?cursor={cursor}&order=asc&limit={limit}&include_failed=false",
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

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
