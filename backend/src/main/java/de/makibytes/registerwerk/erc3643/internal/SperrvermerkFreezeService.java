package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.BlockchainApi;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionView;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.erc3643.events.HolderBlockFreezeConfirmedEvent;
import de.makibytes.registerwerk.erc3643.events.HolderBlockNotPropagatedEvent;
import de.makibytes.registerwerk.erc3643.events.HolderBlockReleaseFailedEvent;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Drives a §16 eWpG Sperrvermerk onto the chain and keeps track of what actually arrived (H5). The register-level
 * block is authoritative at all times; nothing here ever unblocks it. For each (block, deployment, wallet) the
 * service
 * <ol>
 *   <li>picks the admin path of the deployment ({@link SperrvermerkFreezeDispatcher}); a standard or chain with no
 *       automated, outcome-tracked freeze is recorded as {@code UNSUPPORTED_ON_CHAIN} with an audit event, an
 *       operator task and an alert, never skipped silently;</li>
 *   <li>submits the freeze through the durable outbox in its own transaction (a failing submission must not poison
 *       the caller's transaction, which used to swallow the failure with a log line); a submission that throws is
 *       recorded as {@code FAILED} with an audit event, a task and an alert;</li>
 *   <li>reads the outcome from the transaction status ({@link #syncOutcome}): SUCCESS -> {@code CONFIRMED} and
 *       {@code holder_block.on_chain_freeze_tx_hash}; FAILED/REPLACED -> {@code FAILED} + event + task (TIMEOUT is not
 *       a verdict: the transaction may still be mined);</li>
 *   <li>re-sends missing/failed freezes, re-reads {@code isFrozen} for CONFIRMED rows and reports drift in the
 *       periodic jobs ({@link #sweep}, {@link #reconcile}).</li>
 * </ol>
 * The same applies to the unfreeze that follows a lifted block, except that a failed release only leaves the wallet
 * frozen (the safe direction) and is reported, retried, never forced.
 *
 * <p>Live gauges for alerting: {@code registerwerk_sperrvermerk_freeze_failed}, {@code _unsupported},
 * {@code _pending}, {@code _release_failed}; counter {@code registerwerk_sperrvermerk_freeze_drift_total}.
 */
@Service
class SperrvermerkFreezeService {

    static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    static final String REASON_PREFIX = "eWpG §16 Sperrvermerk: ";
    /** Automatic retries of a failed freeze/release by the sweep; the nightly reconcile retries without a cap. */
    static final int MAX_AUTO_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(SperrvermerkFreezeService.class);
    private static final EnumSet<Chain> NON_EVM_CHAINS = EnumSet.of(Chain.SOLANA, Chain.STARKNET, Chain.STELLAR, Chain.CANTON);
    private static final int DETAIL_MAX = 1000;
    private static final int TASK_REF_MAX = 128;

    /** Outcome of one {@link #reconcile} pass, for logging and tests. */
    record Summary(int verified, int resent, int drift, int unsupported, int errors) {
    }

    private final AssetHolderRepository holders;
    private final AssetDeploymentRepository deployments;
    private final AssetLookupPort assets;
    private final HolderBlockFreezeRepository freezes;
    private final HolderBlockRepository blocks;
    private final HolderBlockGate gate;
    private final SperrvermerkFreezeDispatcher dispatcher;
    private final OnChainFrozenReader frozenReader;
    private final BlockchainApi blockchain;
    private final EntityTaskPort tasks;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate newTransaction;
    private final Counter driftCounter;

    SperrvermerkFreezeService(AssetHolderRepository holders, AssetDeploymentRepository deployments,
                              AssetLookupPort assets, HolderBlockFreezeRepository freezes,
                              HolderBlockRepository blocks, HolderBlockGate gate,
                              SperrvermerkFreezeDispatcher dispatcher, OnChainFrozenReader frozenReader,
                              BlockchainApi blockchain, EntityTaskPort tasks, ApplicationEventPublisher events,
                              PlatformTransactionManager transactionManager, MeterRegistry meters) {
        this.holders = holders;
        this.deployments = deployments;
        this.assets = assets;
        this.freezes = freezes;
        this.blocks = blocks;
        this.gate = gate;
        this.dispatcher = dispatcher;
        this.frozenReader = frozenReader;
        this.blockchain = blockchain;
        this.tasks = tasks;
        this.events = events;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        gauge(meters, "registerwerk_sperrvermerk_freeze_failed", HolderBlockFreeze.Status.FAILED,
                "Sperrvermerk freezes that could not be submitted or failed on-chain: the wallet may still move "
                        + "although the register blocks it");
        gauge(meters, "registerwerk_sperrvermerk_freeze_unsupported", HolderBlockFreeze.Status.UNSUPPORTED_ON_CHAIN,
                "Sperrvermerk blocks on deployments whose standard/chain has no automated freeze (manual action needed)");
        gauge(meters, "registerwerk_sperrvermerk_freeze_pending", HolderBlockFreeze.Status.SUBMITTED,
                "Sperrvermerk freezes submitted whose on-chain outcome is not final yet");
        gauge(meters, "registerwerk_sperrvermerk_release_failed", HolderBlockFreeze.Status.RELEASE_FAILED,
                "Unfreezes after a lifted Sperrvermerk that failed: the wallet stays frozen on-chain");
        this.driftCounter = Counter.builder("registerwerk_sperrvermerk_freeze_drift_total")
                .description("Times the nightly read-back found a wallet NOT frozen although its Sperrvermerk freeze was confirmed")
                .register(meters);
    }

    private void gauge(MeterRegistry meters, String name, HolderBlockFreeze.Status status, String description) {
        Gauge.builder(name, freezes, r -> (double) r.countByStatus(status)).description(description).register(meters);
    }

    // ── Propagation of a new block ────────────────────────────────────────────

    /**
     * Freezes {@code wallets} on every live deployment of the assets they hold (or of {@code assetId} only when the
     * block is asset-scoped). Idempotent: a (block, deployment, wallet) whose freeze is already SUBMITTED or
     * CONFIRMED is left alone, so a republished event or the V10 resync cannot double-submit.
     */
    void propagate(UUID blockId, UUID assetId, Collection<String> wallets, String reason) {
        for (String wallet : wallets) {
            List<AssetDeployment> candidates = deploymentsFor(wallet, assetId);
            if (candidates.isEmpty()) {
                reportNoDeployment(blockId, wallet, assetId);
            }
            for (AssetDeployment dep : candidates) {
                if (!SperrvermerkFreezeDispatcher.isLive(dep)) {
                    log.info("Sperrvermerk block={}: deployment {} has no live token yet; the reconcile job freezes it once it has.",
                            blockId, dep.getId());
                    continue;
                }
                Optional<HolderBlockFreeze> existing = freezes
                        .findByHolderBlockIdAndDeploymentIdAndWalletAddress(blockId, dep.getId(), wallet);
                if (existing.isPresent() && (existing.get().getStatus() == HolderBlockFreeze.Status.SUBMITTED
                        || existing.get().getStatus() == HolderBlockFreeze.Status.CONFIRMED)) {
                    continue;
                }
                freezeOne(blockId, dep, wallet, reason);
            }
        }
    }

    /**
     * An asset-scoped block that matches no register row cannot be frozen on-chain. When the asset has EVM
     * deployments that is a compliance gap (the wallet may hold units the register does not attribute to it), not a
     * no-op. A wallet-wide block with no holdings is a no-op.
     */
    private void reportNoDeployment(UUID blockId, String wallet, UUID assetId) {
        if (assetId == null) {
            return;
        }
        List<UUID> evmDeployments = deployments.findByAssetId(assetId).stream()
                .filter(d -> d.getChain() == null || !NON_EVM_CHAINS.contains(d.getChain()))
                .map(AssetDeployment::getId)
                .toList();
        if (evmDeployments.isEmpty()) {
            return;
        }
        log.error("SPERRVERMERK NOT PROPAGATED: ACTIVE block={} wallet={} asset={} matches no register entry, so none of "
                        + "the asset's {} EVM deployment(s) was frozen on-chain - operator must verify the wallet and "
                        + "apply the freeze manually.", blockId, wallet, assetId, evmDeployments.size());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cause", "NO_DEPLOYMENT_MATCHED");
        details.put("walletAddress", wallet);
        details.put("assetId", assetId.toString());
        details.put("evmDeploymentIds", evmDeployments.stream().map(UUID::toString).toList());
        events.publishEvent(new HolderBlockNotPropagatedEvent(blockId, details));
        openTask(assetId, blockId + ":" + assetId + ":" + wallet,
                "Sperrvermerk " + blockId + " names wallet " + wallet + " which matches no register entry of asset "
                        + assetId + ", so no deployment was frozen on-chain. Verify the wallet and freeze it manually.");
    }

    /** Freeze one wallet on one deployment: submit, or record why it could not be. Never throws for chain trouble. */
    void freezeOne(UUID blockId, AssetDeployment dep, String wallet, String reason) {
        SperrvermerkFreezeDispatcher.Route route = dispatcher.route(dep);
        if (!route.supported()) {
            recordUnsupported(blockId, dep, wallet, route.unsupportedReason());
            return;
        }
        try {
            inNewTransaction(() -> {
                HolderBlockFreeze row = lockedOrNew(blockId, dep, wallet);
                UUID txId = dispatcher.freeze(route, dep, wallet, reason);
                row.submitted(HolderBlockFreeze.Status.SUBMITTED, txId, null);
                freezes.save(row);
                return null;
            });
        } catch (DataIntegrityViolationException concurrent) {
            log.info("Sperrvermerk freeze block={} deployment={} wallet={} was recorded concurrently; leaving it to the other run.",
                    blockId, dep.getId(), wallet);
        } catch (RuntimeException e) {
            String detail = truncate(e.getMessage() != null ? e.getMessage() : e.toString());
            inNewTransaction(() -> {
                HolderBlockFreeze row = lockedOrNew(blockId, dep, wallet);
                row.failed(HolderBlockFreeze.Status.FAILED, detail);
                freezes.save(row);
                report(blockId, dep, wallet, "SUBMISSION_FAILED", detail);
                return null;
            });
        }
    }

    private void recordUnsupported(UUID blockId, AssetDeployment dep, String wallet, String reason) {
        inNewTransaction(() -> {
            HolderBlockFreeze row = lockedOrNew(blockId, dep, wallet);
            if (row.getStatus() == HolderBlockFreeze.Status.UNSUPPORTED_ON_CHAIN) {
                return null; // already reported once
            }
            row.transitionTo(HolderBlockFreeze.Status.UNSUPPORTED_ON_CHAIN, truncate(reason));
            freezes.save(row);
            report(blockId, dep, wallet, "UNSUPPORTED_ON_CHAIN", reason);
            return null;
        });
    }

    // ── Release after a lifted block ──────────────────────────────────────────

    /**
     * Reconciles instead of "unfreeze the lifted block's asset": per wallet and deployment, releases the freeze only
     * when no remaining blocking block still covers it (a freeze applied for asset A while another block covered
     * asset B used to be stranded when the last block was lifted, 6-25).
     */
    void release(UUID blockId, Collection<String> wallets) {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (String wallet : wallets) {
            for (AssetDeployment dep : deploymentsFor(wallet, null)) {
                if (SperrvermerkFreezeDispatcher.isLive(dep)) {
                    targets.put(dep.getId() + "|" + wallet, new Target(dep, wallet));
                }
            }
        }
        // rows of this block whose deployment the register no longer leads to (a holder row was removed)
        for (HolderBlockFreeze row : freezes.findByHolderBlockId(blockId)) {
            deployments.findById(row.getDeploymentId()).ifPresent(dep ->
                    targets.putIfAbsent(dep.getId() + "|" + row.getWalletAddress(), new Target(dep, row.getWalletAddress())));
        }
        for (Target target : targets.values()) {
            releaseOne(blockId, target.deployment(), target.wallet());
        }
    }

    private record Target(AssetDeployment deployment, String wallet) {
    }

    void releaseOne(UUID blockId, AssetDeployment dep, String wallet) {
        Optional<HolderBlockFreeze> existing = freezes
                .findByHolderBlockIdAndDeploymentIdAndWalletAddress(blockId, dep.getId(), wallet);
        if (existing.isPresent() && (existing.get().getStatus() == HolderBlockFreeze.Status.RELEASED
                || existing.get().getStatus() == HolderBlockFreeze.Status.RELEASE_SUBMITTED)) {
            return;
        }
        if (gate.isBlockedForAsset(wallet, dep.getAssetId())) {
            log.info("Sperrvermerk lifted for wallet={} but a block still covers asset={} - not unfreezing deployment={}.",
                    wallet, dep.getAssetId(), dep.getId());
            existing.ifPresent(row -> markReleased(row.getId(), "another blocking Sperrvermerk still covers the wallet; the freeze stays"));
            return;
        }
        SperrvermerkFreezeDispatcher.Route route = dispatcher.route(dep);
        if (!route.supported()) {
            existing.ifPresent(row -> markReleased(row.getId(), "nothing to release: no automated freeze exists for this deployment"));
            return;
        }
        try {
            inNewTransaction(() -> {
                HolderBlockFreeze row = lockedOrNew(blockId, dep, wallet);
                UUID txId = dispatcher.release(route, dep, wallet);
                row.submitted(HolderBlockFreeze.Status.RELEASE_SUBMITTED, txId, null);
                freezes.save(row);
                return null;
            });
        } catch (DataIntegrityViolationException concurrent) {
            log.info("Sperrvermerk release block={} deployment={} wallet={} was recorded concurrently.",
                    blockId, dep.getId(), wallet);
        } catch (RuntimeException e) {
            String detail = truncate(e.getMessage() != null ? e.getMessage() : e.toString());
            inNewTransaction(() -> {
                HolderBlockFreeze row = lockedOrNew(blockId, dep, wallet);
                row.failed(HolderBlockFreeze.Status.RELEASE_FAILED, detail);
                freezes.save(row);
                reportReleaseFailed(blockId, dep, wallet, detail);
                return null;
            });
        }
    }

    private void markReleased(UUID rowId, String detail) {
        inNewTransaction(() -> {
            freezes.findByIdForUpdate(rowId).ifPresent(row -> {
                row.transitionTo(HolderBlockFreeze.Status.RELEASED, detail);
                freezes.save(row);
            });
            return null;
        });
    }

    // ── Outcome of a submitted transaction ────────────────────────────────────

    /** Called for every transaction status change; acts only when the transaction is a Sperrvermerk (un)freeze. */
    void onTransactionStatus(String txHash) {
        blockchain.findByTxHash(txHash).ifPresent(tx -> {
            for (HolderBlockFreeze row : freezes.findByTxId(tx.id())) {
                syncIsolated(row.getId());
            }
        });
    }

    private void syncIsolated(UUID rowId) {
        try {
            inNewTransaction(() -> {
                syncOutcome(rowId);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Sperrvermerk freeze outcome sync failed for row {}: {}", rowId, e.getMessage(), e);
        }
    }

    /** Idempotent: acts only on a SUBMITTED / RELEASE_SUBMITTED row whose transaction has a verdict. */
    void syncOutcome(UUID rowId) {
        HolderBlockFreeze row = freezes.findByIdForUpdate(rowId).orElse(null);
        if (row == null || row.getTxId() == null || (row.getStatus() != HolderBlockFreeze.Status.SUBMITTED
                && row.getStatus() != HolderBlockFreeze.Status.RELEASE_SUBMITTED)) {
            return;
        }
        Optional<BlockchainTransactionView> found = blockchain.findTransaction(row.getTxId());
        if (found.isEmpty()) {
            log.warn("Sperrvermerk freeze row {} waits for transaction {} which is not tracked", rowId, row.getTxId());
            return;
        }
        BlockchainTransactionView tx = found.get();
        boolean freezing = row.getStatus() == HolderBlockFreeze.Status.SUBMITTED;
        String hash = tx.minedTxHash() != null ? tx.minedTxHash() : tx.txHash();
        switch (tx.status()) {
            case "SUCCESS" -> {
                row.setTxHash(hash);
                if (freezing) {
                    row.transitionTo(HolderBlockFreeze.Status.CONFIRMED, null);
                    freezes.save(row);
                    blocks.recordOnChainFreezeTxHash(row.getHolderBlockId(), hash);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("walletAddress", row.getWalletAddress());
                    details.put("deploymentId", row.getDeploymentId().toString());
                    details.put("txHash", hash);
                    events.publishEvent(new HolderBlockFreezeConfirmedEvent(row.getHolderBlockId(), details));
                    log.info("Sperrvermerk freeze confirmed: block={} deployment={} wallet={} tx={}",
                            row.getHolderBlockId(), row.getDeploymentId(), row.getWalletAddress(), hash);
                } else {
                    row.transitionTo(HolderBlockFreeze.Status.RELEASED, null);
                    freezes.save(row);
                }
            }
            case "FAILED", "REPLACED" -> {
                String detail = truncate("transaction " + hash + " " + tx.status()
                        + (tx.errorMessage() != null ? ": " + tx.errorMessage() : ""));
                AssetDeployment dep = deployments.findById(row.getDeploymentId()).orElse(null);
                if (freezing) {
                    row.transitionTo(HolderBlockFreeze.Status.FAILED, detail);
                    freezes.save(row);
                    report(row.getHolderBlockId(), dep, row.getDeploymentId(), row.getWalletAddress(), "TX_FAILED", detail);
                } else {
                    row.transitionTo(HolderBlockFreeze.Status.RELEASE_FAILED, detail);
                    freezes.save(row);
                    reportReleaseFailed(row.getHolderBlockId(), dep, row.getDeploymentId(), row.getWalletAddress(), detail);
                }
            }
            default -> log.debug("Sperrvermerk row {}: transaction {} is {} - still in flight", rowId, hash, tx.status());
        }
    }

    // ── Periodic jobs ─────────────────────────────────────────────────────────

    /**
     * Frequent safety net: reads the outcome of submitted rows (the status event is the fast path, this catches a
     * missed one) and retries failed freezes/releases with a backoff, up to {@link #MAX_AUTO_ATTEMPTS}.
     */
    void sweep() {
        for (HolderBlockFreeze row : freezes.findByStatusIn(
                List.of(HolderBlockFreeze.Status.SUBMITTED, HolderBlockFreeze.Status.RELEASE_SUBMITTED))) {
            syncIsolated(row.getId());
        }
        for (HolderBlockFreeze row : freezes.findByStatusIn(
                List.of(HolderBlockFreeze.Status.FAILED, HolderBlockFreeze.Status.RELEASE_FAILED))) {
            if (!retryDue(row)) {
                continue;
            }
            try {
                retry(row);
            } catch (RuntimeException e) {
                log.error("Sperrvermerk retry failed for row {}: {}", row.getId(), e.getMessage(), e);
            }
        }
    }

    private boolean retryDue(HolderBlockFreeze row) {
        if (row.getAttempts() >= MAX_AUTO_ATTEMPTS) {
            return false;
        }
        Duration backoff = Duration.ofMinutes(10L * Math.max(1, row.getAttempts()));
        return row.getUpdatedAt().plus(backoff).isBefore(Instant.now());
    }

    private void retry(HolderBlockFreeze row) {
        AssetDeployment dep = deployments.findById(row.getDeploymentId()).orElse(null);
        if (dep == null) {
            return;
        }
        if (row.getStatus() == HolderBlockFreeze.Status.RELEASE_FAILED) {
            releaseOne(row.getHolderBlockId(), dep, row.getWalletAddress());
            return;
        }
        blocks.findById(row.getHolderBlockId())
                .filter(b -> HolderBlock.BLOCKING.contains(b.getStatus()))
                .ifPresent(b -> freezeOne(b.getId(), dep, row.getWalletAddress(), REASON_PREFIX + b.getLegalBasis()));
    }

    /**
     * Nightly: for every block that still blocks (ACTIVE or EXPIRY_REVIEW), makes sure each live deployment of each
     * covered wallet is frozen on-chain. Missing and failed freezes are re-sent, CONFIRMED rows are checked against
     * the token's {@code isFrozen}, and a wallet found NOT frozen is drift: counted, reported (event, task) and
     * re-frozen. Unsupported deployments stay reported (gauge) without a new event each night.
     */
    Summary reconcile() {
        int verified = 0;
        int resent = 0;
        int drift = 0;
        int unsupported = 0;
        int errors = 0;
        for (HolderBlock block : blocks.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)) {
            String reason = REASON_PREFIX + block.getLegalBasis();
            for (String wallet : walletsOf(block)) {
                for (AssetDeployment dep : deploymentsFor(wallet, block.getAssetId())) {
                    if (!SperrvermerkFreezeDispatcher.isLive(dep)) {
                        continue;
                    }
                    try {
                        switch (reconcileOne(block.getId(), dep, wallet, reason)) {
                            case VERIFIED -> verified++;
                            case RESENT -> resent++;
                            case DRIFT -> drift++;
                            case UNSUPPORTED -> unsupported++;
                            case UNCHANGED -> { }
                        }
                    } catch (RuntimeException e) {
                        errors++;
                        log.error("Sperrvermerk reconcile failed for block={} deployment={} wallet={}: {}",
                                block.getId(), dep.getId(), wallet, e.getMessage(), e);
                    }
                }
            }
        }
        Summary summary = new Summary(verified, resent, drift, unsupported, errors);
        if (resent > 0 || drift > 0 || errors > 0) {
            log.warn("Sperrvermerk freeze reconcile: {}", summary);
        } else {
            log.info("Sperrvermerk freeze reconcile: {}", summary);
        }
        return summary;
    }

    private enum Reconciled { VERIFIED, RESENT, DRIFT, UNSUPPORTED, UNCHANGED }

    private Reconciled reconcileOne(UUID blockId, AssetDeployment dep, String wallet, String reason) {
        HolderBlockFreeze row = freezes
                .findByHolderBlockIdAndDeploymentIdAndWalletAddress(blockId, dep.getId(), wallet).orElse(null);
        if (row == null || row.getStatus() == HolderBlockFreeze.Status.FAILED
                || row.getStatus() == HolderBlockFreeze.Status.RELEASED
                || row.getStatus() == HolderBlockFreeze.Status.RELEASE_SUBMITTED
                || row.getStatus() == HolderBlockFreeze.Status.RELEASE_FAILED) {
            freezeOne(blockId, dep, wallet, reason);
            return Reconciled.RESENT;
        }
        switch (row.getStatus()) {
            case SUBMITTED -> {
                syncIsolated(row.getId());
                return Reconciled.UNCHANGED;
            }
            case UNSUPPORTED_ON_CHAIN -> {
                if (dispatcher.route(dep).supported()) {
                    freezeOne(blockId, dep, wallet, reason);
                    return Reconciled.RESENT;
                }
                return Reconciled.UNSUPPORTED;
            }
            default -> {
                return verify(row, blockId, dep, wallet, reason);
            }
        }
    }

    private Reconciled verify(HolderBlockFreeze row, UUID blockId, AssetDeployment dep, String wallet, String reason) {
        Optional<Boolean> frozen = frozenReader.isFrozen(dep, wallet);
        if (frozen.isEmpty()) {
            return Reconciled.UNCHANGED;
        }
        if (frozen.get()) {
            inNewTransaction(() -> {
                freezes.findByIdForUpdate(row.getId()).ifPresent(r -> {
                    r.verifiedNow();
                    freezes.save(r);
                });
                return null;
            });
            return Reconciled.VERIFIED;
        }
        driftCounter.increment();
        String detail = "isFrozen(" + wallet + ") is false on deployment " + dep.getId()
                + " although the freeze was confirmed (tx " + row.getTxHash() + "): re-freezing.";
        inNewTransaction(() -> {
            freezes.findByIdForUpdate(row.getId()).ifPresent(r -> {
                r.drifted();
                freezes.save(r);
            });
            report(blockId, dep, wallet, "DRIFT", detail);
            return null;
        });
        freezeOne(blockId, dep, wallet, reason);
        return Reconciled.DRIFT;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The block's own wallet plus, for an entity-scoped block, every wallet the entity holds units on. */
    private Set<String> walletsOf(HolderBlock block) {
        Set<String> wallets = new LinkedHashSet<>();
        String own = AddressNormalizer.normalize(block.getWalletAddress());
        if (own != null && !own.isBlank()) {
            wallets.add(own);
        }
        if (block.getEntityId() != null) {
            for (AssetHolder h : holders.findActiveByInvestorId(block.getEntityId())) {
                String w = AddressNormalizer.normalize(h.getWalletAddress());
                if (w != null && !w.isBlank()) {
                    wallets.add(w);
                }
            }
        }
        return wallets;
    }

    private List<AssetDeployment> deploymentsFor(String wallet, UUID assetId) {
        return holders.findByWalletAddressIn(List.of(wallet)).stream()
                .map(AssetHolder::getAssetId)
                .filter(id -> assetId == null || assetId.equals(id))
                .distinct()
                .flatMap(id -> deployments.findByAssetId(id).stream())
                .toList();
    }

    private HolderBlockFreeze lockedOrNew(UUID blockId, AssetDeployment dep, String wallet) {
        return freezes.findByHolderBlockIdAndDeploymentIdAndWalletAddress(blockId, dep.getId(), wallet)
                .flatMap(r -> freezes.findByIdForUpdate(r.getId()))
                .orElseGet(() -> new HolderBlockFreeze(blockId, dep.getId(), wallet));
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        return newTransaction.execute(status -> work.get());
    }

    private void report(UUID blockId, AssetDeployment dep, String wallet, String cause, String detail) {
        report(blockId, dep, dep.getId(), wallet, cause, detail);
    }

    private void report(UUID blockId, AssetDeployment dep, UUID deploymentId, String wallet, String cause, String detail) {
        log.error("SPERRVERMERK NOT ENFORCED ON-CHAIN: block={} wallet={} deployment={} cause={}: {}",
                blockId, wallet, deploymentId, cause, detail);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cause", cause);
        details.put("walletAddress", wallet);
        details.put("deploymentId", deploymentId.toString());
        if (dep != null) {
            details.put("assetId", dep.getAssetId().toString());
        }
        details.put("detail", detail);
        events.publishEvent(new HolderBlockNotPropagatedEvent(blockId, details));
        if (dep != null) {
            openTask(dep.getAssetId(), blockId + ":" + deploymentId + ":" + wallet,
                    "Sperrvermerk " + blockId + " is not enforced on-chain for wallet " + wallet + " on deployment "
                            + deploymentId + " (" + cause + "): " + detail
                            + " The register-level block applies; freeze the wallet manually or fix the cause "
                            + "(the retry/reconcile jobs keep trying where a freeze is possible).");
        }
    }

    private void reportReleaseFailed(UUID blockId, AssetDeployment dep, String wallet, String detail) {
        reportReleaseFailed(blockId, dep, dep.getId(), wallet, detail);
    }

    private void reportReleaseFailed(UUID blockId, AssetDeployment dep, UUID deploymentId, String wallet, String detail) {
        log.error("SPERRVERMERK RELEASE FAILED: block={} wallet={} deployment={} stays frozen on-chain: {}",
                blockId, wallet, deploymentId, detail);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("walletAddress", wallet);
        details.put("deploymentId", deploymentId.toString());
        if (dep != null) {
            details.put("assetId", dep.getAssetId().toString());
        }
        details.put("detail", detail);
        events.publishEvent(new HolderBlockReleaseFailedEvent(blockId, details));
        if (dep != null) {
            openTask(dep.getAssetId(), blockId + ":" + deploymentId + ":" + wallet + ":release",
                    "The Sperrvermerk " + blockId + " was lifted but the on-chain unfreeze of wallet " + wallet
                            + " on deployment " + deploymentId + " failed: " + detail
                            + " The wallet stays frozen on-chain; retry or repair it.");
        }
    }

    private void openTask(UUID assetId, String ref, String detail) {
        assets.findById(assetId).map(AssetLookupPort.AssetInfo::issuerId).ifPresent(issuerId ->
                tasks.open(issuerId, EntityTask.SPERRVERMERK_FREEZE_NOT_PROPAGATED, truncate(ref, TASK_REF_MAX), detail, null));
    }

    private static String truncate(String value) {
        return truncate(value, DETAIL_MAX);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
