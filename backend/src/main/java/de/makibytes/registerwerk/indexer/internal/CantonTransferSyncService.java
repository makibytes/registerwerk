package de.makibytes.registerwerk.indexer.internal;

import com.daml.ledger.javaapi.data.*;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.CantonTokenService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
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
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indexes Canton token transfers by streaming the Ledger API transaction feed.
 *
 * <p>At startup, opens one {@code TransactionService.getTransactions} gRPC stream per enabled
 * CANTON chain config and resumes from the last persisted offset in
 * {@code indexer_state.last_synced_signature}. We filter for {@code Created} and {@code Archived}
 * events on the Daml Token Standard {@code Holding} template. Daml's Archived event carries only the
 * consumed contract's ID, never its former argument payload, so {@link CantonHoldingSnapshot} is a
 * durable mirror of the currently open Holdings, keyed by contract ID.
 *
 * <p>Movements are derived per update from the net change per (instrument, owner)
 * ({@link CantonHoldingMovements}): a split books one TRANSFER of the moved part (the change stays
 * with the sender and books nothing), a merge books nothing, real issuance/redemption remain
 * MINT/BURN. Every row carries an increasing {@code log_index} within the update, so several rows
 * for one update id no longer collide, and the {@code deployment_id}/{@code asset_id} resolved from
 * the instrument. An instrument no CONFIRMED deployment on the chain maps to is not booked (counted
 * in {@code registerwerk.indexer.canton.untracked_instrument}); its snapshots are still maintained
 * so a later deployment does not start from unknown holdings.
 *
 * <p>Coverage evidence: the stream only produces state writes when the ledger is busy, so a
 * scheduled heartbeat re-stamps {@code last_synced_at} while the subscription is alive and the
 * indexer is ACTIVE. A broken stream drops its subscription, stops the heartbeat and lets the
 * coverage guard go stale.
 */
@Service
public class CantonTransferSyncService {

    private static final Logger log = LoggerFactory.getLogger(CantonTransferSyncService.class);

    static final int MAX_CONSECUTIVE_ERRORS = 5;

    /** Ledger API v2 offsets are monotonically increasing int64 values; zero starts at genesis. */
    private static final long LEDGER_BEGIN = 0L;

    private final BlockchainClientRegistry registry;
    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final CantonHoldingSnapshotRepository holdingSnapshotRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final IndexerSyncSupport syncSupport;
    private final MeterRegistry meterRegistry;

    /** chainConfigId -> active stream subscription (for cleanup on shutdown). */
    private final Map<UUID, CantonLedgerClient.Subscription> activeSubscriptions =
            new ConcurrentHashMap<>();

    public CantonTransferSyncService(
            BlockchainClientRegistry registry,
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository,
            CantonHoldingSnapshotRepository holdingSnapshotRepository,
            AssetDeploymentRepository assetDeploymentRepository,
            IndexerSyncSupport syncSupport,
            MeterRegistry meterRegistry) {
        this.registry                  = registry;
        this.chainConfigRepository     = chainConfigRepository;
        this.indexerStateRepository    = indexerStateRepository;
        this.tokenTransferRepository   = tokenTransferRepository;
        this.holdingSnapshotRepository = holdingSnapshotRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.syncSupport               = syncSupport;
        this.meterRegistry             = meterRegistry;
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

    /**
     * Liveness evidence for the coverage guard: while the stream subscription is up and the indexer
     * is ACTIVE the ledger is being followed, even when it is quiet and no update arrives.
     */
    @SchedulerLock(name = "cantonStreamHeartbeat", lockAtMostFor = "PT4M")
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void heartbeat() {
        for (UUID chainId : activeSubscriptions.keySet()) {
            try {
                syncSupport.inTransaction(() -> indexerStateRepository
                        .findByChainConfigIdAndIndexerType(chainId, IndexerState.IndexerType.CANTON_STREAM)
                        .filter(s -> s.getStatus() == IndexerState.IndexerStatus.ACTIVE)
                        .ifPresent(s -> {
                            s.setLastSyncedAt(Instant.now());
                            indexerStateRepository.save(s);
                        }));
            } catch (Exception e) {
                log.warn("Canton heartbeat failed for chain {}: {}", chainId, e.getMessage());
            }
        }
    }

    // ── Per-chain subscription ────────────────────────────────────────────────

    private void subscribeToChain(ChainConfig chain) {
        CantonLedgerClient client = (CantonLedgerClient)
                registry.getCantonClientByIdentifier(chain.getIdentifier());

        IndexerState state = getOrCreateIndexerState(chain);
        long beginOffset = resolveBeginOffset(state);
        CantonLedgerClient.Subscription sub = client.subscribeTransactions(
                beginOffset,
                tx -> handleTransaction(chain, tx),
                err -> handleStreamError(chain, err));

        activeSubscriptions.put(chain.getId(), sub);
        log.info("Canton stream subscription started for chain {}", chain.getIdentifier());
    }

    // ── Transaction processing ────────────────────────────────────────────────

    /**
     * Applies one update atomically (rows, snapshots and offset in one transaction). A failure
     * rolls everything back and propagates so the gRPC stream errors out and reconnects from the
     * last committed offset instead of silently skipping the update.
     */
    void handleTransaction(ChainConfig chain, Transaction tx) {
        List<CantonHoldingMovements.Holding> creates = new ArrayList<>();
        List<String> archivedContractIds = new ArrayList<>();

        for (Event event : tx.getEvents()) {
            if (event instanceof CreatedEvent created && isHoldingTemplate(created.getTemplateId())) {
                creates.add(new CantonHoldingMovements.Holding(
                        created.getContractId(),
                        extractPartyField(created.getArguments(), "owner"),
                        extractInstrumentField(created.getArguments()),
                        extractNumericField(created.getArguments(), "amount")));
            } else if (event instanceof ArchivedEvent archived && isHoldingTemplate(archived.getTemplateId())) {
                archivedContractIds.add(archived.getContractId());
            }
        }
        Instant occurredAt = resolveOccurredAt(tx);

        syncSupport.inTransaction(() -> {
            Map<String, AssetDeployment> deployments = deploymentsByInstrument(chain);
            CantonHoldingMovements.Plan plan = CantonHoldingMovements.plan(creates, archivedContractIds,
                    id -> holdingSnapshotRepository.findById(id).map(snap -> new CantonHoldingMovements.Holding(
                            snap.getContractId(), snap.getOwner(), snap.getInstrument(), snap.getAmount())));

            for (String contractId : plan.unresolved()) {
                log.warn("Canton Holding archived with no known snapshot (created before this indexer started) "
                        + "chain={} contractId={} - balance effect unknown, nothing booked.",
                        chain.getIdentifier(), contractId);
                meterRegistry.counter("registerwerk.indexer.canton.unresolved_archive").increment();
            }
            for (CantonHoldingMovements.Movement m : plan.movements()) {
                AssetDeployment deployment = deployments.get(m.instrument());
                if (deployment == null) {
                    log.warn("Canton instrument {} on chain {} maps to no CONFIRMED deployment; movement not booked.",
                            m.instrument(), chain.getIdentifier());
                    meterRegistry.counter("registerwerk.indexer.canton.untracked_instrument").increment();
                    continue;
                }
                recordTransfer(chain, deployment, tx, occurredAt, m);
            }
            plan.consumed().forEach(holdingSnapshotRepository::deleteById);
            for (CantonHoldingMovements.Holding h : plan.survivors()) {
                holdingSnapshotRepository.save(new CantonHoldingSnapshot(
                        h.contractId(), chain.getId(), h.instrument(), h.owner(), h.amount()));
            }

            IndexerState state = getOrCreateIndexerState(chain);
            state.setLastSyncedSignature(Long.toString(tx.getOffset()));
            state.setLastSyncedAt(Instant.now());
            state.setConsecutiveErrors(0);
            state.setLastError(null);
            state.setStatus(IndexerState.IndexerStatus.ACTIVE);
            indexerStateRepository.save(state);
        });
    }

    private Map<String, AssetDeployment> deploymentsByInstrument(ChainConfig chain) {
        Map<String, AssetDeployment> byInstrument = new HashMap<>();
        for (AssetDeployment d : assetDeploymentRepository.findByChainConfigId(chain.getId())) {
            if (d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED
                    && d.getContractAddress() != null && !d.getContractAddress().isBlank()) {
                byInstrument.putIfAbsent(d.getContractAddress(), d);
            }
        }
        return byInstrument;
    }

    private void recordTransfer(ChainConfig chain, AssetDeployment deployment, Transaction tx, Instant occurredAt,
                                CantonHoldingMovements.Movement m) {
        String txHash = tx.getUpdateId();
        if (tokenTransferRepository.existsByChainConfigIdAndTxHashAndLogIndexAndContractAddress(
                chain.getId(), txHash, m.index(), m.instrument())) {
            return; // replay of an already booked update
        }
        TokenTransfer tt = new TokenTransfer();
        tt.setChainConfigId(chain.getId());
        tt.setDeploymentId(deployment.getId());
        tt.setAssetId(deployment.getAssetId());
        tt.setContractAddress(m.instrument());
        tt.setFromAddress(m.from());
        tt.setToAddress(m.to());
        tt.setAmount(m.amount());
        tt.setEventType(m.type());
        tt.setOccurredAt(occurredAt);
        tt.setTxHash(txHash);
        tt.setLogIndex(m.index());
        // A Canton synchronizer commits transactions atomically - once a participant observes an update on
        // the Ledger API stream, that commit is final; there is no probabilistic-finality/reorg model.
        tt.setFinalityStatus(FinalityLevel.FINALIZED);
        tokenTransferRepository.save(tt);
        log.debug("Canton transfer recorded: chain={} type={} instrument={} from={} to={} amount={}",
                chain.getIdentifier(), m.type(), m.instrument(), m.from(), m.to(), m.amount());
    }

    /** Daml's {@code Transaction} carries the ledger's effective time for the commit. */
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

    private void handleStreamError(ChainConfig chain, Throwable err) {
        log.error("Canton stream error for chain {}: {}", chain.getIdentifier(), err.getMessage(), err);
        CantonLedgerClient.Subscription broken = activeSubscriptions.remove(chain.getId());
        if (broken != null) {
            broken.close();
        }
        syncSupport.recordFailure(chain.getId(), chain.getIdentifier(), IndexerState.IndexerType.CANTON_STREAM,
                err instanceof Exception ex ? ex : new IllegalStateException(err), MAX_CONSECUTIVE_ERRORS);

        boolean inError = indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), IndexerState.IndexerType.CANTON_STREAM)
                .map(s -> s.getStatus() == IndexerState.IndexerStatus.ERROR).orElse(false);
        // Attempt reconnect after a brief delay unless the indexer is in ERROR
        if (!inError) {
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
