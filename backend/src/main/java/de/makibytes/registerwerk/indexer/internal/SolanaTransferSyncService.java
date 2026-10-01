package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks Solana SPL / Token-2022 balances with a <strong>balance-delta</strong> ingester.
 *
 * <p>Every CONFIRMED Solana deployment's mint is tracked (registered at startup and by a short
 * scheduled pass after a deployment is confirmed; the per-mint {@code solana_mint_sync_cursor} row
 * is seeded at that moment, which is the evidence the coverage guard looks for). The
 * {@link IndexerState.IndexerType#SOLANA_POLL} poll walks {@code getSignaturesForAddress(mint)}
 * back to the per-mint cursor, page by page, fetches every transaction with {@code jsonParsed}
 * encoding and books what actually happened to the tracked mint: the difference between
 * {@code meta.postTokenBalances} and {@code meta.preTokenBalances}, summed per <em>owner</em>
 * (wallet, not token account). This is independent of which instruction moved the tokens
 * (transfer, transferChecked, mintTo, burn, batched or CPI-driven) and of the token program
 * ({@code spl-token} and {@code spl-token-2022} alike). Owners with a positive delta are matched
 * against owners with a negative delta into {@code TRANSFER} rows ({@link BalanceDeltaPairing});
 * an unmatched positive residual is a {@code MINT}, an unmatched negative one a {@code BURN}. A
 * Token-2022 transfer fee therefore appears as a small burn next to the transfer: the balance
 * delta is authoritative for the register.
 *
 * <p>Failure semantics: a {@code getTransaction} that errors or returns {@code null} aborts the
 * mint's whole poll - nothing is persisted and the cursor does not advance, so the transaction is
 * retried on the next tick. The cursor moves to the newest signature only in the same database
 * transaction that persists all rows of the poll.
 *
 * <p>Known caveat: signatures are listed by the <em>mint</em> address, which the ledger attaches to
 * every instruction that names the mint ({@code transferChecked}, {@code mintTo}, {@code burn}, ...).
 * A plain {@code transfer} instruction does not reference the mint and would be missed; the deployed
 * Token-2022 presets use {@code transferChecked}. If plain {@code transfer} support is ever needed the
 * token accounts must be polled as well.
 */
@Service
public class SolanaTransferSyncService {

    private static final Logger log = LoggerFactory.getLogger(SolanaTransferSyncService.class);

    static final int MAX_CONSECUTIVE_ERRORS = 5;
    static final int SIGNATURE_PAGE_LIMIT = 1000;
    /** Safety valve against a runaway pagination loop (1000 pages = 1M signatures in one poll). */
    private static final int MAX_SIGNATURE_PAGES = 1000;

    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final SolanaMintSyncCursorRepository mintSyncCursorRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final IndexerSyncSupport syncSupport;
    private final ExplorerUrlBuilder explorerUrlBuilder;
    private final RestClient restClient;
    /** Overridable in tests to exercise pagination without a thousand-signature fixture. */
    private int signaturePageLimit = SIGNATURE_PAGE_LIMIT;

    public SolanaTransferSyncService(
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            SolanaMintSyncCursorRepository mintSyncCursorRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            IndexerSyncSupport syncSupport,
            ExplorerUrlBuilder explorerUrlBuilder,
            RestClient.Builder restClientBuilder) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.mintSyncCursorRepository = mintSyncCursorRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.syncSupport = syncSupport;
        this.explorerUrlBuilder = explorerUrlBuilder;
        this.restClient = restClientBuilder.build();
    }

    void setSignaturePageLimit(int signaturePageLimit) {
        this.signaturePageLimit = signaturePageLimit;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Logs the WebSocket stub state per enabled chain; the polling ingester is what feeds the register. */
    @PostConstruct
    public void startGeyserSubscriptions() {
        try {
            List<ChainConfig> solanaChains = chainConfigRepository
                    .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.SOLANA);
            for (ChainConfig chain : solanaChains) {
                subscribeToSplLogs(chain);
            }
            log.info("Solana Geyser/WebSocket subscriptions initialised for {} chain(s).",
                    solanaChains.size());
        } catch (Exception e) {
            log.error("Failed to initialise Solana Geyser subscriptions: {}", e.getMessage(), e);
        }
    }

    /** P4-02: mints of already confirmed deployments are registered (cursor rows seeded) at startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void registerMintsOnStartup() {
        try {
            for (ChainConfig chain : chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.SOLANA)) {
                registerConfirmedMints(chain);
            }
        } catch (Exception e) {
            log.warn("Solana mint registration at startup failed (retried by the scheduled pass): {}", e.getMessage());
        }
    }

    /**
     * Picks up deployments confirmed after startup. The indexer module cannot subscribe to the asset
     * module's {@code DeploymentConfirmedEvent} (asset already depends on indexer), so a short
     * scheduled pass registers the mint - which seeds its cursor row - and polls the chain right away
     * when something new appeared.
     */
    @SchedulerLock(name = "solanaMintRegistration", lockAtMostFor = "PT2M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void registerNewMints() {
        try {
            for (ChainConfig chain : chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.SOLANA)) {
                try {
                    if (registerConfirmedMints(chain) > 0) {
                        pollChain(chain);
                    }
                } catch (Exception e) {
                    log.error("Error registering Solana mints on chain {}: {}", chain.getIdentifier(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error in Solana mint registration: {}", e.getMessage(), e);
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    /** Every 10 minutes, catches up every tracked mint from its cursor. */
    @SchedulerLock(name = "solanaTransferSync", lockAtMostFor = "PT9M")
    @Scheduled(cron = "0 */10 * * * *")
    public void pollMissedTransactions() {
        try {
            for (ChainConfig chain : chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.SOLANA)) {
                try {
                    pollChain(chain);
                } catch (Exception e) {
                    log.error("Error polling Solana chain {}: {}", chain.getIdentifier(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error in Solana polling scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Registers the mint of every CONFIRMED deployment on {@code chain} (idempotent) and seeds its
     * {@code solana_mint_sync_cursor} row. Returns the number of newly registered mints.
     */
    public int registerConfirmedMints(ChainConfig chain) {
        AtomicInteger registered = new AtomicInteger();
        syncSupport.inTransaction(() -> {
            for (AssetDeployment d : confirmedMintDeployments(chain).values()) {
                if (mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), d.getContractAddress())
                        .isEmpty()) {
                    SolanaMintSyncCursor c = new SolanaMintSyncCursor();
                    c.setChainConfigId(chain.getId());
                    c.setMintAddress(d.getContractAddress());
                    mintSyncCursorRepository.save(c);
                    registered.incrementAndGet();
                    log.info("Registered SPL mint {} on chain {} (deployment {}).", d.getContractAddress(),
                            chain.getIdentifier(), d.getId());
                }
            }
        });
        return registered.get();
    }

    /**
     * One catch-up pass over all tracked mints of {@code chain}. Each mint is its own transaction; a
     * failing mint neither blocks the others nor advances its own cursor. The chain-level
     * {@code SOLANA_POLL} state is stamped on every pass - including "no mints" - so the coverage
     * guard and the monitor can tell an alive poller from a dead one.
     */
    public void pollChain(ChainConfig chain) {
        Map<String, AssetDeployment> mints = confirmedMintDeployments(chain);
        Exception firstFailure = null;
        int totalNew = 0;
        for (AssetDeployment deployment : mints.values()) {
            try {
                totalNew += pollMint(chain, deployment);
            } catch (Exception e) {
                log.warn("Error polling mint {} on chain {}: {}", deployment.getContractAddress(),
                        chain.getIdentifier(), e.getMessage());
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (firstFailure != null) {
            syncSupport.recordFailure(chain.getId(), chain.getIdentifier(),
                    IndexerState.IndexerType.SOLANA_POLL, firstFailure, MAX_CONSECUTIVE_ERRORS);
            return;
        }
        syncSupport.inTransaction(() -> {
            IndexerState state = loadOrCreatePollState(chain);
            state.setLastSyncedAt(Instant.now());
            state.setConsecutiveErrors(0);
            state.setLastError(null);
            state.setStatus(IndexerState.IndexerStatus.ACTIVE);
            indexerStateRepository.save(state);
        });
        if (totalNew > 0) {
            log.info("Solana chain {}: poll booked {} new movement(s).", chain.getIdentifier(), totalNew);
        }
    }

    private Map<String, AssetDeployment> confirmedMintDeployments(ChainConfig chain) {
        Map<String, AssetDeployment> byMint = new LinkedHashMap<>();
        for (AssetDeployment d : assetDeploymentRepository.findByChainConfigId(chain.getId())) {
            if (d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED
                    && d.getContractAddress() != null && !d.getContractAddress().isBlank()) {
                byMint.putIfAbsent(d.getContractAddress(), d);
            }
        }
        return byMint;
    }

    /** Catches one mint up to the chain head; returns the number of newly persisted rows. */
    private int pollMint(ChainConfig chain, AssetDeployment deployment) {
        String mint = deployment.getContractAddress();
        String until = mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), mint)
                .map(SolanaMintSyncCursor::getLastSyncedSignature).orElse(null);

        List<Map<String, Object>> signatures = fetchAllSignatures(chain.getRpcUrl(), mint, until);
        Collections.reverse(signatures); // oldest first
        String newest = null;
        List<TokenTransfer> rows = new ArrayList<>();
        for (Map<String, Object> sigInfo : signatures) {
            String sig = (String) sigInfo.get("signature");
            if (sig == null) {
                continue;
            }
            newest = sig; // the cursor follows every signature seen, including failed ones
            // A non-null err means the transaction was included in a block but reverted: it moved nothing.
            if (sigInfo.get("err") != null) {
                log.debug("Skipping failed Solana tx {} on chain {}: err={}", sig, chain.getIdentifier(),
                        sigInfo.get("err"));
                continue;
            }
            Map<String, Object> result = fetchTransaction(chain.getRpcUrl(), sig);
            rows.addAll(toTransfers(chain, deployment, sig, sigInfo, result));
        }

        final String newestSignature = newest;
        AtomicInteger saved = new AtomicInteger();
        syncSupport.inTransaction(() -> {
            for (TokenTransfer row : rows) {
                if (!tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndexAndContractAddress(
                        row.getChainConfigId(), row.getTxHash(), row.getLogIndex(), row.getContractAddress())) {
                    tokenTransferRepository.save(row);
                    saved.incrementAndGet();
                }
            }
            SolanaMintSyncCursor cursor = mintSyncCursorRepository
                    .findByChainConfigIdAndMintAddress(chain.getId(), mint)
                    .orElseGet(() -> {
                        SolanaMintSyncCursor c = new SolanaMintSyncCursor();
                        c.setChainConfigId(chain.getId());
                        c.setMintAddress(mint);
                        return c;
                    });
            if (newestSignature != null) {
                cursor.setLastSyncedSignature(newestSignature);
            }
            // Stamped on every successful pass (also an empty one) - this is the coverage evidence.
            cursor.setLastSyncedAt(Instant.now());
            mintSyncCursorRepository.save(cursor);
        });
        return saved.get();
    }

    // ── Transaction decoding ──────────────────────────────────────────────────

    /**
     * Books the movements of {@code mint} in one fetched {@code getTransaction} result. Package
     * visible for fixture tests. Throws when the result carries no usable time reference: a register
     * row must never be stamped with processing time.
     */
    @SuppressWarnings("unchecked")
    List<TokenTransfer> toTransfers(ChainConfig chain, AssetDeployment deployment, String signature,
                                    Map<String, Object> sigInfo, Map<String, Object> result) {
        Object meta = result.get("meta");
        if (meta instanceof Map<?, ?> m && m.get("err") != null) {
            return List.of(); // failed at getTransaction level too (belt and braces)
        }
        String mint = deployment.getContractAddress();
        List<Object> accountKeys = accountKeys(result);
        Map<String, BigInteger> deltaByOwner = new LinkedHashMap<>();
        Map<String, Object> trimmedBalances = new HashMap<>();
        String[] programId = new String[1];
        Integer[] decimals = new Integer[1];
        if (meta instanceof Map<?, ?> metaMap) {
            for (String side : List.of("preTokenBalances", "postTokenBalances")) {
                int sign = side.startsWith("pre") ? -1 : 1;
                List<Object> kept = new ArrayList<>();
                if (metaMap.get(side) instanceof List<?> entries) {
                    for (Object o : entries) {
                        if (!(o instanceof Map<?, ?> entry) || !mint.equals(entry.get("mint"))) {
                            continue;
                        }
                        kept.add(entry);
                        String owner = ownerOf((Map<String, Object>) entry, accountKeys);
                        BigInteger raw = rawAmount((Map<String, Object>) entry);
                        deltaByOwner.merge(owner, raw.multiply(BigInteger.valueOf(sign)), BigInteger::add);
                        if (entry.get("programId") instanceof String pid) {
                            programId[0] = pid;
                        }
                        if (entry.get("uiTokenAmount") instanceof Map<?, ?> ui && ui.get("decimals") instanceof Number d) {
                            decimals[0] = d.intValue();
                        }
                    }
                }
                trimmedBalances.put(side, kept);
            }
        }
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltaByOwner.forEach((owner, delta) -> {
            if (delta.signum() != 0) {
                deltas.put(owner, new BigDecimal(delta));
            }
        });
        if (deltas.isEmpty()) {
            return List.of();
        }

        Long slot = numberOf(result.get("slot"));
        if (slot == null) {
            slot = numberOf(sigInfo == null ? null : sigInfo.get("slot"));
        }
        Long blockTime = numberOf(result.get("blockTime"));
        if (blockTime == null) {
            blockTime = numberOf(sigInfo == null ? null : sigInfo.get("blockTime"));
        }
        if (blockTime == null) {
            throw new IllegalStateException("Solana transaction " + signature
                    + " has no blockTime; refusing to stamp a register row with processing time");
        }

        List<BalanceDeltaPairing.Leg> legs = BalanceDeltaPairing.pair(deltas);
        List<TokenTransfer> rows = new ArrayList<>(legs.size());
        for (int i = 0; i < legs.size(); i++) {
            BalanceDeltaPairing.Leg leg = legs.get(i);
            TokenTransfer t = new TokenTransfer();
            t.setChainConfigId(chain.getId());
            t.setDeploymentId(deployment.getId());
            t.setAssetId(deployment.getAssetId());
            t.setContractAddress(mint);
            t.setFromAddress(leg.from());
            t.setToAddress(leg.to());
            t.setAmount(leg.amount());
            t.setEventType(leg.type());
            t.setTxHash(signature);
            t.setSlot(slot);
            t.setBlockNumber(slot);
            t.setLogIndex(i);
            t.setOccurredAt(IndexerTimestamps.clampFuture(Instant.ofEpochSecond(blockTime), String.valueOf(chain)));
            t.setExplorerTxUrl(explorerUrlBuilder.buildTxUrl(chain, signature));
            // getSignaturesForAddress and getTransaction both use commitment=finalized: rooted, no reorg window.
            t.setFinalityStatus(FinalityLevel.FINALIZED);
            Map<String, Object> raw = new HashMap<>();
            raw.put("source", "solana-balance-delta");
            raw.put("slot", slot);
            raw.put("version", result.get("version"));
            raw.put("programId", programId[0]);
            raw.put("decimals", decimals[0]);
            if (meta instanceof Map<?, ?> mm) {
                raw.put("fee", mm.get("fee"));
            }
            raw.putAll(trimmedBalances);
            t.setRawData(raw);
            rows.add(t);
        }
        return rows;
    }

    private static List<Object> accountKeys(Map<String, Object> result) {
        if (result.get("transaction") instanceof Map<?, ?> tx && tx.get("message") instanceof Map<?, ?> msg
                && msg.get("accountKeys") instanceof List<?> keys) {
            return new ArrayList<>(keys);
        }
        return List.of();
    }

    /** The wallet owning the token account; falls back to the token account address for legacy data. */
    private static String ownerOf(Map<String, Object> balance, List<Object> accountKeys) {
        if (balance.get("owner") instanceof String owner && !owner.isBlank()) {
            return owner;
        }
        if (balance.get("accountIndex") instanceof Number idx && idx.intValue() >= 0
                && idx.intValue() < accountKeys.size()) {
            Object key = accountKeys.get(idx.intValue());
            if (key instanceof Map<?, ?> km && km.get("pubkey") instanceof String pk) {
                return pk;
            }
            if (key instanceof String s) {
                return s;
            }
        }
        throw new IllegalStateException("Solana token balance entry has neither owner nor resolvable account: " + balance);
    }

    private static BigInteger rawAmount(Map<String, Object> balance) {
        if (balance.get("uiTokenAmount") instanceof Map<?, ?> ui && ui.get("amount") instanceof String amount) {
            return new BigInteger(amount);
        }
        throw new IllegalStateException("Solana token balance entry has no uiTokenAmount.amount: " + balance);
    }

    private static Long numberOf(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    // ── RPC ───────────────────────────────────────────────────────────────────

    private void subscribeToSplLogs(ChainConfig chain) {
        // Full WebSocket subscription requires a dedicated async WebSocket client. This stub logs
        // intent and relies on the polling ingester until the WebSocket layer is wired up.
        if (chain.getWsUrl() == null || chain.getWsUrl().isBlank()) {
            log.debug("Chain {} has no wsUrl configured; Geyser subscription skipped.", chain.getIdentifier());
            return;
        }
        log.info("Would subscribe to SPL Token logs on {} via WebSocket at {}. "
                        + "(WebSocket subscription stub - polling is active.)",
                chain.getIdentifier(), chain.getWsUrl());
    }

    /**
     * Pages {@code getSignaturesForAddress} with {@code before} until the {@code until} boundary (the
     * cursor) or the start of history is reached. Newest first. Any RPC error aborts the poll - a
     * partial listing would otherwise let the cursor jump past unseen signatures.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllSignatures(String rpcUrl, String mint, String until) {
        List<Map<String, Object>> all = new ArrayList<>();
        String before = null;
        for (int page = 0; page < MAX_SIGNATURE_PAGES; page++) {
            Map<String, Object> options = new HashMap<>();
            options.put("limit", signaturePageLimit);
            options.put("commitment", "finalized");
            if (until != null) {
                options.put("until", until);
            }
            if (before != null) {
                options.put("before", before);
            }
            List<Object> params = new ArrayList<>();
            params.add(mint);
            params.add(options);
            Object result = rpc(rpcUrl, "getSignaturesForAddress", params);
            if (!(result instanceof List<?> list)) {
                throw new IllegalStateException("getSignaturesForAddress returned no list for " + mint);
            }
            all.addAll((List<Map<String, Object>>) list);
            if (list.size() < signaturePageLimit) {
                return all;
            }
            Object last = ((Map<String, Object>) list.get(list.size() - 1)).get("signature");
            if (!(last instanceof String s) || s.equals(before)) {
                throw new IllegalStateException("getSignaturesForAddress pagination stalled for " + mint);
            }
            before = s;
        }
        throw new IllegalStateException("getSignaturesForAddress pagination exceeded " + MAX_SIGNATURE_PAGES
                + " pages for " + mint);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchTransaction(String rpcUrl, String signature) {
        Object result = rpc(rpcUrl, "getTransaction", List.of(signature, Map.of(
                "encoding", "jsonParsed",
                "maxSupportedTransactionVersion", 0,
                "commitment", "finalized")));
        if (!(result instanceof Map<?, ?> map)) {
            throw new IllegalStateException("getTransaction returned no result for " + signature);
        }
        return (Map<String, Object>) map;
    }

    /** JSON-RPC call; transport errors, RPC errors and a missing result all throw. */
    @SuppressWarnings("unchecked")
    private Object rpc(String rpcUrl, String method, List<?> params) {
        Map<String, Object> response = restClient.post()
                .uri(rpcUrl)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException(method + " returned an empty response");
        }
        if (response.get("error") != null) {
            throw new IllegalStateException(method + " failed: " + response.get("error"));
        }
        return response.get("result");
    }

    private IndexerState loadOrCreatePollState(ChainConfig chain) {
        return indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), IndexerState.IndexerType.SOLANA_POLL)
                .orElseGet(() -> {
                    IndexerState s = new IndexerState();
                    s.setChainConfigId(chain.getId());
                    s.setIndexerType(IndexerState.IndexerType.SOLANA_POLL);
                    s.setStatus(IndexerState.IndexerStatus.ACTIVE);
                    return s;
                });
    }
}
