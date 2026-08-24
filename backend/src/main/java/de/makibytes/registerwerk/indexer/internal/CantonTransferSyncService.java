package de.makibytes.registerwerk.indexer.internal;

import com.daml.ledger.javaapi.data.*;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.CantonTokenService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.chain.api.CantonLedgerClient;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indexes Canton token transfers by streaming the Ledger API transaction feed.
 *
 * <p>Mirrors {@link SolanaTransferSyncService} in structure. At startup, opens one
 * {@code TransactionService.getTransactions} gRPC stream per enabled CANTON chain config.
 * Resumes from the last persisted offset stored in {@code indexer_state.last_synced_signature}.
 *
 * <p>The stream emits {@link Transaction} events. We filter for {@code Created} and
 * {@code Archived} events on the Daml Token Standard {@code Holding} template. Daml's Archived
 * event carries only the consumed contract's ID, never its former argument payload — so on its
 * own it cannot tell us which instrument/owner/amount was actually consumed. {@link
 * CantonHoldingSnapshot} is a durable (restart-surviving) mirror of "currently open Holdings" the
 * indexer has seen Created, keyed by contract ID, so an Archived event can resolve what it's
 * consuming before the snapshot is deleted.
 *
 * <p>Within one transaction, an Archived Holding is matched to a Created Holding of the same
 * instrument (in order) as a single {@code TRANSFER} row — this is the common shape of a Daml
 * Token Standard transfer choice (archive sender's holding, create recipient's, atomically).
 * Unmatched Archives become {@code BURN} rows using the resolved snapshot; unmatched Creates
 * become {@code MINT} rows. A resolvable-but-missing snapshot (e.g. the holding was created
 * before this indexer ever ran, so no snapshot row exists) is recorded as a {@code BURN} of
 * unknown amount rather than silently dropped, and logged at WARN for investigation.
 *
 * <p>For reconnect resilience, errors bump {@code consecutive_errors} and set
 * {@code status = ERROR} after {@link #MAX_CONSECUTIVE_ERRORS} failures.
 */
@Service
public class CantonTransferSyncService {

    private static final Logger log = LoggerFactory.getLogger(CantonTransferSyncService.class);

    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    /** Ledger API v2 offsets are monotonically increasing int64 values; zero starts at genesis. */
    private static final long LEDGER_BEGIN = 0L;

    private static final String ZERO_ADDRESS = "0x0";

    private final BlockchainClientRegistry registry;
    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final CantonHoldingSnapshotRepository holdingSnapshotRepository;

    /** Lazily-injected self-reference so {@code handleTransaction} is invoked THROUGH the Spring
     *  proxy (required for {@code @Transactional} to actually apply) rather than via a direct
     *  {@code this.handleTransaction(...)} call from the RxJava subscribe lambda below, which
     *  bypasses the proxy entirely and silently turns the annotation into a no-op. */
    @Lazy
    private final CantonTransferSyncService self;

    /** chainConfigId → active stream subscription (for cleanup on shutdown). */
    private final Map<UUID, CantonLedgerClient.Subscription> activeSubscriptions =
            new ConcurrentHashMap<>();

    public CantonTransferSyncService(
            BlockchainClientRegistry registry,
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            CantonHoldingSnapshotRepository holdingSnapshotRepository,
            @Lazy CantonTransferSyncService self) {
        this.registry                 = registry;
        this.chainConfigRepository    = chainConfigRepository;
        this.indexerStateRepository   = indexerStateRepository;
        this.tokenTransferRepository  = tokenTransferRepository;
        this.holdingSnapshotRepository = holdingSnapshotRepository;
        this.self                    = self;
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    @PostConstruct
    public void startStreamSubscriptions() {
        List<ChainConfig> cantonChains = chainConfigRepository.findByEnabledTrue()
                .stream()
                .filter(c -> c.getChainType() == ChainConfig.ChainType.CANTON)
                .toList();

        for (ChainConfig chain : cantonChains) {
            try {
                subscribeToChain(chain);
            } catch (Exception e) {
                log.error("Failed to start Canton stream for chain {}: {}",
                        chain.getIdentifier(), e.getMessage(), e);
            }
        }
        log.info("Canton indexer started: {} chain(s)", cantonChains.size());
    }

    @PreDestroy
    public void stopSubscriptions() {
        activeSubscriptions.values().forEach(CantonLedgerClient.Subscription::close);
        activeSubscriptions.clear();
        log.info("Canton indexer subscriptions stopped.");
    }

    // ── Per-chain subscription ────────────────────────────────────────────────

    private void subscribeToChain(ChainConfig chain) {
        CantonLedgerClient client = (CantonLedgerClient)
                registry.getCantonClientByIdentifier(chain.getIdentifier());

        IndexerState state = getOrCreateIndexerState(chain);
        long beginOffset = resolveBeginOffset(state);
        CantonLedgerClient.Subscription sub = client.subscribeTransactions(
                beginOffset,
                tx -> self.handleTransaction(chain, state, tx),
                err -> handleStreamError(chain, state, err));

        activeSubscriptions.put(chain.getId(), sub);
        log.info("Canton stream subscription started for chain {}", chain.getIdentifier());
    }

    // ── Transaction processing ────────────────────────────────────────────────

    /** One Holding Created event's extracted fields, pending resolution against any matching
     *  Archived event in the same transaction (see class-level note on transfer matching). */
    private record HoldingCreate(String contractId, String owner, String instrument, BigDecimal amount) {}

    @Transactional
    protected void handleTransaction(ChainConfig chain, IndexerState state, Transaction tx) {
        List<HoldingCreate> creates = new ArrayList<>();
        List<String> archivedContractIds = new ArrayList<>();

        for (Event event : tx.getEvents()) {
            if (event instanceof CreatedEvent created && isHoldingTemplate(created.getTemplateId())) {
                creates.add(new HoldingCreate(
                        created.getContractId(),
                        extractPartyField(created.getArguments(), "owner"),
                        extractInstrumentField(created.getArguments()),
                        extractNumericField(created.getArguments(), "amount")));
            } else if (event instanceof ArchivedEvent archived && isHoldingTemplate(archived.getTemplateId())) {
                archivedContractIds.add(archived.getContractId());
            }
        }

        Instant occurredAt = resolveOccurredAt(tx);

        // Match each Archived Holding to a same-instrument Created Holding in this same
        // transaction, in order — the common shape of a Daml Token Standard transfer choice
        // (archive sender's holding, create recipient's, atomically). Whatever's left over is an
        // independent mint/burn.
        Map<String, Deque<HoldingCreate>> createsByInstrument = new HashMap<>();
        for (HoldingCreate c : creates) {
            createsByInstrument.computeIfAbsent(c.instrument(), k -> new ArrayDeque<>()).add(c);
        }
        Set<String> matchedContractIds = new HashSet<>();

        for (String contractId : archivedContractIds) {
            CantonHoldingSnapshot snapshot = holdingSnapshotRepository.findById(contractId).orElse(null);
            if (snapshot == null) {
                log.warn("Canton Holding archived with no known snapshot (created before this "
                                + "indexer started, or snapshot already consumed) chain={} contractId={} "
                                + "— recording as a burn of unknown amount for investigation.",
                        chain.getIdentifier(), contractId);
                recordTransfer(chain, tx, occurredAt, "unknown", "unknown", ZERO_ADDRESS, BigDecimal.ZERO,
                        TokenTransfer.EventType.BURN);
                continue;
            }

            Deque<HoldingCreate> candidates = createsByInstrument.get(snapshot.getInstrument());
            HoldingCreate match = (candidates != null) ? candidates.poll() : null;
            if (match != null) {
                matchedContractIds.add(match.contractId());
                recordTransfer(chain, tx, occurredAt, snapshot.getInstrument(), snapshot.getOwner(), match.owner(),
                        match.amount(), TokenTransfer.EventType.TRANSFER);
                holdingSnapshotRepository.save(new CantonHoldingSnapshot(
                        match.contractId(), chain.getId(), match.instrument(), match.owner(), match.amount()));
            } else {
                recordTransfer(chain, tx, occurredAt, snapshot.getInstrument(), snapshot.getOwner(), ZERO_ADDRESS,
                        snapshot.getAmount(), TokenTransfer.EventType.BURN);
            }
            holdingSnapshotRepository.deleteById(contractId);
        }

        // Unmatched creates are fresh issuance (mint) — persist a snapshot so a later Archive of
        // this same contract can resolve it.
        for (HoldingCreate c : creates) {
            if (matchedContractIds.contains(c.contractId())) continue;
            recordTransfer(chain, tx, occurredAt, c.instrument(), ZERO_ADDRESS, c.owner(), c.amount(),
                    TokenTransfer.EventType.MINT);
            holdingSnapshotRepository.save(
                    new CantonHoldingSnapshot(c.contractId(), chain.getId(), c.instrument(), c.owner(), c.amount()));
        }

        // Advance the cursor
        state.setLastSyncedSignature(Long.toString(tx.getOffset()));
        state.setLastSyncedAt(Instant.now());
        state.setConsecutiveErrors(0);
        state.setStatus(IndexerState.IndexerStatus.ACTIVE);
        indexerStateRepository.save(state);
    }

    private void recordTransfer(ChainConfig chain, Transaction tx, Instant occurredAt, String instrument,
                                 String from, String to, BigDecimal amount, TokenTransfer.EventType eventType) {
        TokenTransfer tt = new TokenTransfer();
        tt.setChainConfigId(chain.getId());
        tt.setContractAddress(instrument);
        tt.setFromAddress(from);
        tt.setToAddress(to);
        tt.setAmount(amount);
        tt.setEventType(eventType);
        tt.setOccurredAt(occurredAt);
        tt.setTxHash(tx.getUpdateId());
        // a Canton synchronizer commits transactions atomically — once a participant
        // observes a transaction on the Ledger API stream, that commit is final; there is no
        // probabilistic-finality/reorg model for a permissioned synchronizer's confirmed offsets
        // the way there is for EVM/Starknet. FINAL matches the entity default; set explicitly
        // here for clarity/documentation, mirroring the same reasoning applied to Stellar.
        tt.setFinalityStatus(FinalityLevel.FINALIZED);
        tokenTransferRepository.save(tt);
        log.debug("Canton transfer recorded: chain={} type={} instrument={} from={} to={} amount={}",
                chain.getIdentifier(), eventType, instrument, from, to, amount);
    }

    /** Daml's {@code Transaction} carries the ledger's effective time for the commit — falls back
     *  to processing time only if that's ever unavailable (should not happen in practice), rather
     *  than silently discarding a real chain timestamp. Could not be compiled/verified against the
     *  actual {@code com.daml.ledger.javaapi.data.Transaction} class in this environment (no
     *  network access to resolve the DAML SDK here) — flagged as a best-effort fix pending a real
     *  {@code -Pcanton} build. */
    private Instant resolveOccurredAt(Transaction tx) {
        try {
            return tx.getEffectiveAt();
        } catch (Exception e) {
            log.warn("Canton transaction {} has no effective-time available; falling back to processing time",
                    tx.getUpdateId());
            return Instant.now();
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private void handleStreamError(ChainConfig chain, IndexerState state, Throwable err) {
        log.error("Canton stream error for chain {}: {}", chain.getIdentifier(), err.getMessage(), err);

        int errors = state.getConsecutiveErrors() + 1;
        state.setConsecutiveErrors(errors);
        state.setLastError(err.getMessage());
        if (errors >= MAX_CONSECUTIVE_ERRORS) {
            state.setStatus(IndexerState.IndexerStatus.ERROR);
            log.error("Canton indexer for chain {} set to ERROR after {} consecutive failures",
                    chain.getIdentifier(), errors);
        }
        indexerStateRepository.save(state);

        // Attempt reconnect after a brief delay unless too many errors
        if (errors < MAX_CONSECUTIVE_ERRORS) {
            try {
                Thread.sleep(5_000);
                subscribeToChain(chain);
            } catch (Exception ex) {
                log.error("Failed to reconnect Canton stream for chain {}: {}",
                        chain.getIdentifier(), ex.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IndexerState getOrCreateIndexerState(ChainConfig chain) {
        return indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), IndexerState.IndexerType.CANTON_STREAM)
                .orElseGet(() -> {
                    IndexerState s = new IndexerState();
                    s.setChainConfigId(chain.getId());
                    s.setIndexerType(IndexerState.IndexerType.CANTON_STREAM);
                    s.setStatus(IndexerState.IndexerStatus.ACTIVE);
                    return indexerStateRepository.save(s);
                });
    }

    private long resolveBeginOffset(IndexerState state) {
        String lastOffset = state.getLastSyncedSignature();
        if (lastOffset == null || lastOffset.isBlank()) return LEDGER_BEGIN;
        try {
            return Long.parseLong(lastOffset);
        } catch (NumberFormatException invalidLegacyOffset) {
            throw new IllegalStateException(
                    "Invalid Canton Ledger API v2 offset: " + lastOffset, invalidLegacyOffset);
        }
    }

    private boolean isHoldingTemplate(Identifier templateId) {
        return CantonTokenService.TOKEN_STANDARD_PACKAGE.equals(templateId.getPackageId())
                && "Lfdt.Tokenstandard.Holding".equals(templateId.getModuleName() + "." + templateId.getEntityName());
    }

    private String extractPartyField(DamlRecord args, String fieldName) {
        return args.getFields().stream()
                .filter(f -> fieldName.equals(f.getLabel().orElse("")))
                .findFirst()
                .map(f -> ((Party) f.getValue()).getValue())
                .orElse("unknown");
    }

    private BigDecimal extractNumericField(DamlRecord args, String fieldName) {
        return args.getFields().stream()
                .filter(f -> fieldName.equals(f.getLabel().orElse("")))
                .findFirst()
                .map(f -> ((Numeric) f.getValue()).getValue())
                .orElse(BigDecimal.ZERO);
    }

    private String extractInstrumentField(DamlRecord args) {
        return args.getFields().stream()
                .filter(f -> "instrument".equals(f.getLabel().orElse("")))
                .findFirst()
                .map(f -> f.getValue().toString())
                .orElse("unknown");
    }
}
