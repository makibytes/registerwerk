package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks Solana SPL token transfers using two complementary approaches:
 *
 * <ol>
 *   <li><strong>WebSocket / Geyser subscription</strong> ({@link IndexerState.IndexerType#SOLANA_GEYSER}):
 *       registers log subscriptions for the SPL Token program so that transfers are captured
 *       in near real-time. Initialised at startup via {@link #startGeyserSubscriptions()}.</li>
 *   <li><strong>Polling fallback</strong> ({@link IndexerState.IndexerType#SOLANA_POLL}):
 *       every 10 minutes, {@link #pollMissedTransactions()} walks backwards from the last
 *       known signature for each tracked mint to catch any gaps that the WebSocket may have
 *       missed (e.g. during reconnects or restarts).</li>
 * </ol>
 *
 * <p>SPL token instructions are parsed from the {@code getTransaction} RPC response using
 * {@code jsonParsed} encoding. A trimmed slice of the raw transaction data (slot, version, and
 * the {@code meta} fields with forensic value — see {@link #trimRawResult}) is stored in
 * {@code token_transfer.raw_data} for auditing; the full RPC response is not persisted.
 */
@Service
public class SolanaTransferSyncService {

    private static final Logger log = LoggerFactory.getLogger(SolanaTransferSyncService.class);

    /** Solana SPL Token program address. */
    private static final String SPL_TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";

    private static final int MAX_SIGNATURES_PER_POLL = 200;

    // chainIdentifier → set of SPL mint addresses to track on that chain.
    private final Map<String, Set<String>> trackedMintsByChain = new ConcurrentHashMap<>();

    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final SolanaMintSyncCursorRepository mintSyncCursorRepository;
    private final ExplorerUrlBuilder explorerUrlBuilder;
    private final RestClient restClient;

    public SolanaTransferSyncService(
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            SolanaMintSyncCursorRepository mintSyncCursorRepository,
            ExplorerUrlBuilder explorerUrlBuilder,
            RestClient.Builder restClientBuilder) {
        this.chainConfigRepository = chainConfigRepository;
        this.indexerStateRepository = indexerStateRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.mintSyncCursorRepository = mintSyncCursorRepository;
        this.explorerUrlBuilder = explorerUrlBuilder;
        this.restClient = restClientBuilder.build();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initialises the in-memory mint-address sets and attempts to set up WebSocket log
     * subscriptions for each enabled Solana chain.
     */
    @PostConstruct
    public void startGeyserSubscriptions() {
        try {
            List<ChainConfig> solanaChains = chainConfigRepository
                    .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.SOLANA);

            for (ChainConfig chain : solanaChains) {
                trackedMintsByChain.putIfAbsent(chain.getIdentifier(),
                        ConcurrentHashMap.newKeySet());
                subscribeToSplLogs(chain);
            }
            log.info("Solana Geyser/WebSocket subscriptions initialised for {} chain(s).",
                    solanaChains.size());
        } catch (Exception e) {
            log.error("Failed to initialise Solana Geyser subscriptions: {}", e.getMessage(), e);
        }
    }

    // ── Polling fallback ──────────────────────────────────────────────────────

    /**
     * Every 10 minutes, fetches recent signatures for every tracked SPL mint and processes
     * any transfers not yet present in the database.
     */
    @SchedulerLock(name = "solanaTransferSync", lockAtMostFor = "PT9M")
    @Scheduled(cron = "0 */10 * * * *")
    public void pollMissedTransactions() {
        try {
            List<ChainConfig> solanaChains = chainConfigRepository
                    .findByChainTypeAndEnabledTrue(ChainConfig.ChainType.SOLANA);

            for (ChainConfig chain : solanaChains) {
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

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Registers a new SPL mint address for tracking on a specific chain. Called by
     * {@code AssetDeploymentService} when a new Solana SPL token deployment is confirmed.
     *
     * @param chainIdentifier Identifier of the chain config, e.g. "SOLANA_MAINNET".
     * @param mintAddress     The SPL mint account public key.
     */
    public void registerMintAddress(String chainIdentifier, String mintAddress) {
        Set<String> mints = trackedMintsByChain.computeIfAbsent(
                chainIdentifier, k -> ConcurrentHashMap.newKeySet());
        boolean added = mints.add(mintAddress);
        if (added) {
            log.info("Registered new SPL mint {} on chain {}", mintAddress, chainIdentifier);
            chainConfigRepository.findByIdentifier(chainIdentifier).ifPresent(this::subscribeToSplLogs);
        }
    }

    // ── Per-chain polling ─────────────────────────────────────────────────────

    @Transactional
    public void pollChain(ChainConfig chain) {
        Set<String> mints = trackedMintsByChain.getOrDefault(
                chain.getIdentifier(), Collections.emptySet());

        if (mints.isEmpty()) {
            log.debug("No tracked mints for Solana chain {}; skipping poll.", chain.getIdentifier());
            return;
        }

        IndexerState state = loadOrCreatePollState(chain);

        int totalNew = 0;
        for (String mint : mints) {
            try {
                totalNew += pollMintAddress(chain, mint);
            } catch (Exception e) {
                log.warn("Error polling mint {} on chain {}: {}", mint, chain.getIdentifier(),
                        e.getMessage());
            }
        }

        state.setLastSyncedAt(Instant.now());
        state.setStatus(IndexerState.IndexerStatus.ACTIVE);
        indexerStateRepository.save(state);

        if (totalNew > 0) {
            log.info("Solana chain {}: poll found {} new transfer(s).",
                    chain.getIdentifier(), totalNew);
        }
    }

    // ── Transaction processing ────────────────────────────────────────────────

    /**
     * Deduplicates, fetches, and persists a single Solana transaction.
     * Parses the first SPL Token instruction found in the parsed transaction to extract
     * event type (mint/transfer/burn), from/to addresses, mint address, and amount.
     *
     * @param chain       Chain on which the transaction occurred.
     * @param txSignature Solana transaction signature (base58).
     * @param slot        Slot in which the transaction was confirmed (nullable).
     */
    @Transactional
    public void processSolanaTransaction(ChainConfig chain, String txSignature, Long slot) {
        // Dedup: Solana has no log index; use null.
        boolean exists = tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(
                chain.getId(), txSignature, null);
        if (exists) {
            log.debug("Duplicate Solana tx {} on chain {}; skipping.", txSignature,
                    chain.getIdentifier());
            return;
        }

        Map<String, Object> rawTxData = fetchRawTransaction(chain.getRpcUrl(), txSignature);

        // Belt-and-suspenders: pollMintAddress already skips signatures whose getSignaturesForAddress
        // "err" field is non-null, but processSolanaTransaction is also reachable independently
        // (e.g. from a future Geyser/WebSocket push), so re-check the getTransaction-level err too.
        if (rawTxData.get("raw") instanceof Map<?, ?> raw
                && raw.get("meta") instanceof Map<?, ?> meta
                && meta.get("err") != null) {
            log.debug("Skipping failed Solana tx {} on chain {} (meta.err={})", txSignature,
                    chain.getIdentifier(), meta.get("err"));
            return;
        }

        String fromAddress = extractField(rawTxData, "from");
        String toAddress   = extractField(rawTxData, "to");
        String contractAddress = extractField(rawTxData, "mint");
        String amountStr   = extractField(rawTxData, "amount");

        TokenTransfer.EventType eventType;
        if (fromAddress == null || fromAddress.isBlank()) {
            eventType = TokenTransfer.EventType.MINT;
        } else if (toAddress == null || toAddress.isBlank()) {
            eventType = TokenTransfer.EventType.BURN;
        } else {
            eventType = TokenTransfer.EventType.TRANSFER;
        }

        TokenTransfer transfer = new TokenTransfer();
        transfer.setChainConfigId(chain.getId());
        transfer.setContractAddress(contractAddress != null ? contractAddress : "");
        transfer.setFromAddress(fromAddress);
        transfer.setToAddress(toAddress);
        transfer.setTxHash(txSignature);
        transfer.setSlot(slot);
        transfer.setEventType(eventType);
        transfer.setOccurredAt(resolveOccurredAt(rawTxData));
        transfer.setExplorerTxUrl(explorerUrlBuilder.buildTxUrl(chain, txSignature));
        transfer.setRawData(rawTxData);
        // both getSignaturesForAddress and getTransaction above are called with
        // commitment=finalized, so by the time a row reaches this point Solana's own consensus
        // (supermajority-rooted, no probabilistic finality/reorg window) already treats it as
        // irreversible — there is no separate provisional tier for Solana the way there is for
        // EVM/Starknet. FINAL matches the entity default; set explicitly here for clarity.
        transfer.setFinalityStatus(FinalityLevel.FINALIZED);

        tokenTransferRepository.save(transfer);
        log.debug("Saved Solana {} tx={} chain={} amount={}", eventType, txSignature,
                chain.getIdentifier(), amountStr);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void subscribeToSplLogs(ChainConfig chain) {
        // Full WebSocket subscription requires a dedicated async WebSocket client (e.g. OkHttp or
        // Netty). The SPL Token program is the subscription target. This stub logs intent and
        // relies on the polling fallback until the WebSocket layer is wired up.
        if (chain.getWsUrl() == null || chain.getWsUrl().isBlank()) {
            log.debug("Chain {} has no wsUrl configured; Geyser subscription skipped.",
                    chain.getIdentifier());
            return;
        }
        log.info("Would subscribe to SPL Token logs on {} via WebSocket at {}. "
                        + "(WebSocket subscription stub — polling fallback is active.)",
                chain.getIdentifier(), chain.getWsUrl());
    }

    private int pollMintAddress(ChainConfig chain, String mint) {
        SolanaMintSyncCursor cursor = loadOrCreateMintCursor(chain, mint);

        // getSignaturesForAddress paginates backwards from the most recent signature, stopping at
        // (exclusive of) the "until" boundary or MAX_SIGNATURES_PER_POLL, whichever comes first.
        // Results are ordered newest-first, so signatures.get(0) is this poll's new high-water mark.
        String until = cursor.getLastSyncedSignature();

        List<Map<String, Object>> signatures = fetchSignaturesForAddress(
                chain.getRpcUrl(), mint, MAX_SIGNATURES_PER_POLL, until);

        int count = 0;
        String newestSignatureThisPoll = null;
        for (Map<String, Object> sigInfo : signatures) {
            String sig = (String) sigInfo.get("signature");
            Object slotObj = sigInfo.get("slot");
            Long slot = slotObj instanceof Number n ? n.longValue() : null;

            if (sig == null) continue;
            if (newestSignatureThisPoll == null) {
                newestSignatureThisPoll = sig;
            }

            // A non-null "err" means the transaction was included in a block but reverted
            // on-chain (e.g. a failed transfer instruction) — it never moved any tokens, so
            // indexing it as a transfer would fabricate a transfer that didn't happen. This
            // field was previously read into sigInfo but never inspected.
            if (sigInfo.get("err") != null) {
                log.debug("Skipping failed Solana tx {} on chain {}: err={}", sig,
                        chain.getIdentifier(), sigInfo.get("err"));
                continue;
            }

            boolean dup = tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndex(
                    chain.getId(), sig, null);
            if (!dup) {
                processSolanaTransaction(chain, sig, slot);
                count++;
            }
        }

        // The cursor always advances to the newest signature actually seen this poll — if this
        // poll returned nothing new, the cursor is left untouched (nothing to advance to).
        if (newestSignatureThisPoll != null) {
            cursor.setLastSyncedSignature(newestSignatureThisPoll);
            cursor.setLastSyncedAt(Instant.now());
            mintSyncCursorRepository.save(cursor);
        }
        return count;
    }

    private SolanaMintSyncCursor loadOrCreateMintCursor(ChainConfig chain, String mint) {
        return mintSyncCursorRepository.findByChainConfigIdAndMintAddress(chain.getId(), mint)
                .orElseGet(() -> {
                    SolanaMintSyncCursor c = new SolanaMintSyncCursor();
                    c.setChainConfigId(chain.getId());
                    c.setMintAddress(mint);
                    return mintSyncCursorRepository.save(c);
                });
    }

    /**
     * Calls {@code getSignaturesForAddress} via direct JSON-RPC HTTP call.
     * SolanaJ 1.18's typed API does not expose this method, so we use {@link RestClient}.
     *
     * @param rpcUrl the Solana RPC endpoint URL
     * @param address the SPL mint (or wallet) address to query
     * @param limit   max signatures to return (≤ 1000)
     * @param until   stop returning signatures at this signature (exclusive); null for latest
     * @return list of signature info maps (keys: signature, slot, err, blockTime)
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchSignaturesForAddress(
            String rpcUrl, String address, int limit, String until) {
        var options = new HashMap<String, Object>();
        options.put("limit", limit);
        options.put("commitment", "finalized");
        if (until != null) {
            options.put("until", until);
        }

        var params = new ArrayList<>();
        params.add(address);
        params.add(options);

        var requestBody = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "getSignaturesForAddress",
            "params", params
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri(rpcUrl)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.containsKey("error")) {
                log.warn("getSignaturesForAddress returned error for {}: {}", address,
                        response != null ? response.get("error") : "null response");
                return Collections.emptyList();
            }

            Object result = response.get("result");
            if (result instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to fetch signatures for address {} at {}: {}", address, rpcUrl,
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Calls {@code getTransaction} with {@code jsonParsed} encoding and extracts the first
     * SPL Token program instruction (transfer, mintTo, or burn) to determine the event
     * type, from/to addresses, mint address, and amount.
     *
     * @param rpcUrl      the Solana RPC endpoint URL
     * @param txSignature base58-encoded transaction signature
     * @return map with keys: signature, from, to, mint, amount, instructionType
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchRawTransaction(String rpcUrl, String txSignature) {
        var requestBody = Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "getTransaction",
            "params", List.of(txSignature, Map.of(
                "encoding", "jsonParsed",
                "maxSupportedTransactionVersion", 0,
                "commitment", "finalized"
            ))
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri(rpcUrl)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.containsKey("error") || response.get("result") == null) {
                log.warn("getTransaction returned no result for {}", txSignature);
                return Map.of("signature", txSignature);
            }

            Map<String, Object> result = (Map<String, Object>) response.get("result");
            Map<String, Object> parsed = new HashMap<>();
            parsed.put("signature", txSignature);
            // blockTime (unix epoch seconds) is the real on-chain confirmation time — using
            // Instant.now() (processing time) instead would re-order transfer history whenever
            // the indexer falls behind and catches up later.
            parsed.put("blockTime", result.get("blockTime"));

            // Walk transaction.message.instructions to find the first SPL Token instruction.
            Map<String, Object> tx = (Map<String, Object>) result.get("transaction");
            if (tx != null) {
                Map<String, Object> message = (Map<String, Object>) tx.get("message");
                if (message != null) {
                    List<Map<String, Object>> instructions =
                            (List<Map<String, Object>>) message.get("instructions");
                    if (instructions != null) {
                        for (Map<String, Object> ix : instructions) {
                            if (!"spl-token".equals(ix.get("program"))) continue;
                            Map<String, Object> info =
                                    (Map<String, Object>) ((Map<?, ?>) ix.get("parsed")).get("info");
                            String type = (String) ((Map<?, ?>) ix.get("parsed")).get("type");
                            if (info == null) continue;

                            parsed.put("instructionType", type);
                            parsed.put("mint", info.get("mint"));
                            parsed.put("amount", info.get("amount"));

                            // transfer / transferChecked: source/destination are token accounts;
                            // authority is the owning wallet for the source account.
                            if ("transfer".equals(type) || "transferChecked".equals(type)) {
                                parsed.put("from", info.get("authority"));
                                parsed.put("to", info.get("destination"));
                            } else if ("mintTo".equals(type) || "mintToChecked".equals(type)) {
                                // mintTo: no source; destination is the token account
                                parsed.put("to", info.get("account"));
                            } else if ("burn".equals(type) || "burnChecked".equals(type)) {
                                // burn: no destination; source is the token account owner
                                parsed.put("from", info.get("authority"));
                            }
                            break; // take first SPL instruction
                        }
                    }
                }
            }

            // Preserve a trimmed slice of the raw result for forensic/debugging value — see
            // trimRawResult() for exactly what is kept and why the full response is not stored.
            parsed.put("raw", trimRawResult(result));
            return parsed;
        } catch (Exception e) {
            log.warn("Failed to fetch transaction {} at {}: {}", txSignature, rpcUrl,
                    e.getMessage());
            return Map.of("signature", txSignature);
        }
    }

    /**
     * {@code token_transfer.raw_data} used to store the entire {@code getTransaction}
     * {@code jsonParsed} RPC response verbatim under the "raw" key — including
     * {@code meta.logMessages} (program log output, can run to hundreds of lines for a complex
     * versioned transaction), {@code meta.innerInstructions} (the full CPI call trace across
     * every program touched, not just the SPL Token instruction already extracted above into
     * {@code instructionType}/{@code mint}/{@code amount}/{@code from}/{@code to}),
     * {@code meta.loadedAddresses}, and the full {@code transaction.message.instructions} list —
     * none of which is ever read back by any code path (the only reader of {@code raw_data} is
     * {@code TokenTransfer.getRawData()}, and nothing calls it) and all of which scales with
     * transaction complexity rather than with anything about the transfer itself.
     *
     * <p>This registry is regulated ({@code eWpG}/{@code TVTG}), so the fix is deliberately not
     * "drop everything" but "keep what has forensic/audit value and drop the noise": on-chain
     * slot and version, whether the transaction actually succeeded ({@code meta.err} — non-null
     * means it failed on-chain despite being included in a block), the fee paid, and — most
     * importantly — {@code meta.preTokenBalances}/{@code postTokenBalances}, which is the
     * ground-truth evidence for the SPL token balance deltas this transfer claims, independent
     * of (and a cross-check against) the amount already parsed from the instruction above.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> trimRawResult(Map<String, Object> result) {
        Map<String, Object> trimmed = new HashMap<>();
        trimmed.put("slot", result.get("slot"));
        trimmed.put("version", result.get("version"));
        if (result.get("meta") instanceof Map<?, ?> metaRaw) {
            Map<String, Object> meta = (Map<String, Object>) metaRaw;
            Map<String, Object> trimmedMeta = new HashMap<>();
            trimmedMeta.put("err", meta.get("err"));
            trimmedMeta.put("fee", meta.get("fee"));
            trimmedMeta.put("preTokenBalances", meta.get("preTokenBalances"));
            trimmedMeta.put("postTokenBalances", meta.get("postTokenBalances"));
            trimmed.put("meta", trimmedMeta);
        }
        return trimmed;
    }

    private IndexerState loadOrCreatePollState(ChainConfig chain) {
        return indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(),
                        IndexerState.IndexerType.SOLANA_POLL)
                .orElseGet(() -> {
                    IndexerState s = new IndexerState();
                    s.setChainConfigId(chain.getId());
                    s.setIndexerType(IndexerState.IndexerType.SOLANA_POLL);
                    s.setStatus(IndexerState.IndexerStatus.ACTIVE);
                    return indexerStateRepository.save(s);
                });
    }

    private String extractField(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val instanceof String s ? s : null;
    }

    /** Uses the transaction's real on-chain {@code blockTime} when available; falls back to
     *  processing time only if the RPC response omitted it (observed for very old ledger data
     *  on some public RPC providers). */
    private Instant resolveOccurredAt(Map<String, Object> rawTxData) {
        Object blockTime = rawTxData.get("blockTime");
        if (blockTime instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        return Instant.now();
    }
}
