package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.api.LendingReconciliationTask;
import de.makibytes.registerwerk.lending.api.LendingReconciliationTask.Status;
import de.makibytes.registerwerk.lending.api.LendingReconciliationTaskRepository;
import de.makibytes.registerwerk.lending.events.LendingCollateralReconciliationNeededEvent;
import de.makibytes.registerwerk.lending.events.LendingOperatorActionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 5B-10 (parked T5-12 interim): collateral that left a market outside repay/liquidate is detected
 * by the forced-move listener (by method name) and by the balance guard (by balance, independent
 * of names). Either opens one task per market and marks the market {@code collateral_shortfall},
 * which pauses borrowing in the registry's view. An operator then attributes the outflow to a
 * borrower with {@code reconcileCollateral} (step-up + second approver, submitted through the
 * durable outbox). The operator may also pause borrowing on-chain with {@code setBorrowPaused}.
 */
@Service
@Transactional
public class LendingReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(LendingReconciliationService.class);
    private static final EnumSet<Status> UNRESOLVED = EnumSet.of(Status.OPEN, Status.SUBMITTED);
    private static final java.util.regex.Pattern BYTES32 = java.util.regex.Pattern.compile("^0x[0-9a-fA-F]{64}$");
    private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile("^0x[0-9a-fA-F]{40}$");

    private final LendingMarketRepository marketRepository;
    private final LendingReconciliationTaskRepository taskRepository;
    private final RepoMarketOnchainReader onchainReader;
    private final LendingMarketService marketService;
    private final DurableEvmTransactionGateway durableTransactions;
    private final LendingReleaseGate releaseGate;
    private final ApplicationEventPublisher eventPublisher;
    private final Counter detections;
    private final Counter guardFailures;
    private final TransactionTemplate perMarketTx;

    LendingReconciliationService(
            LendingMarketRepository marketRepository,
            LendingReconciliationTaskRepository taskRepository,
            RepoMarketOnchainReader onchainReader,
            LendingMarketService marketService,
            DurableEvmTransactionGateway durableTransactions,
            LendingReleaseGate releaseGate,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry,
            PlatformTransactionManager txManager) {
        this.marketRepository = marketRepository;
        this.taskRepository = taskRepository;
        this.onchainReader = onchainReader;
        this.marketService = marketService;
        this.durableTransactions = durableTransactions;
        this.releaseGate = releaseGate;
        this.eventPublisher = eventPublisher;
        this.perMarketTx = new TransactionTemplate(txManager);
        this.perMarketTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Gauge.builder("registerwerk_lending_reconciliation_tasks_open", taskRepository,
                        r -> r.countByStatusIn(UNRESOLVED))
                .description("Lending markets with collateral that left outside repay/liquidate and is not yet reconciled")
                .register(meterRegistry);
        this.detections = Counter.builder("registerwerk_lending_collateral_outflow_detected_total")
                .register(meterRegistry);
        this.guardFailures = Counter.builder("registerwerk_lending_balance_guard_read_failures_total")
                .register(meterRegistry);
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    /** Turns the forced-move event into an operator task (and pauses borrowing in the registry view). */
    @ApplicationModuleListener
    void onReconciliationNeeded(LendingCollateralReconciliationNeededEvent event) {
        marketRepository.findById(event.marketId()).ifPresent(market ->
                openTask(market, LendingReconciliationTask.Source.FORCED_TRANSFER_EVENT, null,
                        event.tokenAdminMethod(),
                        "Forced move '" + event.tokenAdminMethod() + "' from the market to " + event.toAddress()
                                + " amount " + event.amount()));
    }

    /** Scheduled balance guard: {@code collateralToken.balanceOf(market) < totalCollateral()}. */
    @SchedulerLock(name = "lendingCollateralBalanceGuard", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    @Scheduled(fixedDelayString = "${registerwerk.lending.balance-guard-interval-ms:300000}", initialDelay = 60_000)
    // No transaction across the RPC reads: each market's DB writes run in their own short transaction.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runBalanceGuard() {
        if (!releaseGate.isReleased()) return;
        for (LendingMarket market : marketRepository.findByStatus(LendingMarketStatus.ACTIVE)) {
            try {
                checkMarket(market);
            } catch (RuntimeException e) {
                guardFailures.increment();
                log.warn("Collateral balance guard could not read market {}: {}", market.getMarketAddress(), e.getMessage());
            }
        }
    }

    void checkMarket(LendingMarket market) {
        String chain = marketService.resolveChainIdentifier(market.getChainConfigId());
        BigInteger held = onchainReader.tokenBalanceOf(chain, market.getCollateralTokenAddress(), market.getMarketAddress());
        BigInteger total = onchainReader.totalCollateral(chain, market.getMarketAddress());
        perMarketTx.executeWithoutResult(status -> applyBalanceCheck(market, held, total));
    }

    private void applyBalanceCheck(LendingMarket detached, BigInteger held, BigInteger total) {
        LendingMarket market = marketRepository.findById(detached.getId()).orElse(detached);
        if (held.compareTo(total) < 0) {
            openTask(market, LendingReconciliationTask.Source.BALANCE_GUARD, total.subtract(held), null,
                    "Market holds " + held + " but records totalCollateral " + total);
        } else if (market.isCollateralShortfall()) {
            market.setCollateralShortfall(false);
            marketRepository.save(market);
            taskRepository.findFirstByMarketIdAndStatusIn(market.getId(), UNRESOLVED).ifPresent(task -> {
                task.setStatus(Status.RESOLVED);
                task.setResolvedAt(Instant.now());
                task.setDetail(truncate("No shortfall observed on chain any more (held " + held + " >= total " + total + ")"));
                taskRepository.save(task);
                eventPublisher.publishEvent(new LendingOperatorActionEvent("COLLATERAL_SHORTFALL_CLEARED",
                        market.getId(), null, "SYSTEM", Map.of("taskId", task.getId().toString())));
            });
        }
    }

    LendingReconciliationTask openTask(LendingMarket market, LendingReconciliationTask.Source source,
                                       BigInteger shortfall, String method, String detail) {
        LendingReconciliationTask task = taskRepository.findFirstByMarketIdAndStatusIn(market.getId(), UNRESOLVED)
                .orElse(null);
        boolean created = task == null;
        if (created) {
            task = new LendingReconciliationTask();
            task.setMarketId(market.getId());
            task.setSource(source);
            detections.increment();
            log.error("ALERT lending market {} ({}) lost collateral outside repay/liquidate ({}): {}. Borrowing is "
                            + "treated as paused; an operator must call setBorrowPaused(true) and reconcileCollateral "
                            + "(with the forced-transfer reference) on the market contract.",
                    market.getId(), market.getMarketAddress(), source, detail);
        }
        if (shortfall != null && shortfall.compareTo(task.getShortfall()) > 0) task.setShortfall(shortfall);
        if (method != null) task.setTokenAdminMethod(method);
        task.setDetail(truncate(detail));
        task = taskRepository.save(task);
        if (!market.isCollateralShortfall()) {
            market.setCollateralShortfall(true);
            marketRepository.save(market);
        }
        if (created) {
            eventPublisher.publishEvent(new LendingOperatorActionEvent("COLLATERAL_SHORTFALL_DETECTED",
                    market.getId(), null, "SYSTEM", Map.of("taskId", task.getId().toString(),
                            "source", source.name(), "shortfall", task.getShortfall().toString())));
        }
        return task;
    }

    // ── Operator actions ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LendingReconciliationTask> listUnresolvedTasks() {
        return taskRepository.findByStatusInOrderByDetectedAtAsc(UNRESOLVED);
    }

    /**
     * Attributes the observed outflow to {@code borrowerWallet}: submits
     * {@code reconcileCollateral(borrower, attributableCollateral, forcedTransferRef)}. The contract
     * enforces the bound (reduction never above the observed shortfall, never an increase).
     */
    public LendingReconciliationTask reconcileCollateral(
            UUID marketId, String borrowerWallet, BigInteger attributableCollateral,
            String forcedTransferRef, String legalBasis, UUID actorId) {
        releaseGate.requireReleased();
        if (borrowerWallet == null || !ADDRESS.matcher(borrowerWallet).matches()) {
            throw new IllegalArgumentException("borrowerWallet must be an EVM address");
        }
        if (attributableCollateral == null || attributableCollateral.signum() < 0) {
            throw new IllegalArgumentException("attributableCollateral must not be negative");
        }
        if (forcedTransferRef == null || !BYTES32.matcher(forcedTransferRef).matches()) {
            throw new IllegalArgumentException("forcedTransferRef must be a 32-byte hex value (the forced-transfer tx hash)");
        }
        if (legalBasis == null || legalBasis.isBlank()) {
            throw new IllegalArgumentException("legalBasis is required");
        }
        LendingMarket market = marketService.requireMarket(marketId);
        LendingReconciliationTask task = taskRepository.findFirstByMarketIdAndStatusIn(marketId, UNRESOLVED)
                .orElseThrow(() -> new IllegalStateException(
                        "No open collateral reconciliation task for market " + marketId));
        Function fn = new Function("reconcileCollateral",
                List.of(new Address(borrowerWallet), new Uint256(attributableCollateral),
                        new Bytes32(hexToBytes32(forcedTransferRef))),
                Collections.emptyList());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("borrower", borrowerWallet);
        params.put("attributableCollateral", attributableCollateral.toString());
        params.put("forcedTransferRef", forcedTransferRef);
        params.put("legalBasis", legalBasis);
        String txHash = durableTransactions.submit(market.getChainConfigId(), market.getMarketAddress(), fn, params);
        task.setStatus(Status.SUBMITTED);
        task.setBorrowerWallet(borrowerWallet);
        task.setAttributedAmount(attributableCollateral);
        task.setForcedTransferRef(forcedTransferRef);
        task.setLegalBasis(truncate(legalBasis));
        task.setReconcileTxHash(txHash);
        task.setResolvedBy(actorId);
        task = taskRepository.save(task);
        eventPublisher.publishEvent(new LendingOperatorActionEvent("COLLATERAL_RECONCILE_SUBMITTED",
                marketId, actorId, "REGISTRY_ADMIN", Map.of("taskId", task.getId().toString(),
                        "borrower", borrowerWallet, "attributableCollateral", attributableCollateral.toString(),
                        "forcedTransferRef", forcedTransferRef, "legalBasis", legalBasis, "txHash", txHash)));
        return task;
    }

    /** Submits {@code setBorrowPaused(paused)} through the durable outbox. */
    public String setBorrowPaused(UUID marketId, boolean paused, String reason, UUID actorId) {
        releaseGate.requireReleased();
        LendingMarket market = marketService.requireMarket(marketId);
        Function fn = new Function("setBorrowPaused", List.of(new Bool(paused)), Collections.emptyList());
        String txHash = durableTransactions.submit(market.getChainConfigId(), market.getMarketAddress(), fn,
                Map.of("paused", paused, "reason", reason == null ? "" : reason));
        eventPublisher.publishEvent(new LendingOperatorActionEvent("BORROW_PAUSE_SUBMITTED", marketId, actorId,
                "REGISTRY_ADMIN", Map.of("paused", paused, "reason", reason == null ? "" : reason, "txHash", txHash)));
        return txHash;
    }

    public record PauseEnforcement(UUID marketId, String marketAddress, String outcome, String txHash) {}

    /**
     * H11: makes the "withdraw-only" state of unverified / legacy markets real on-chain. For every ACTIVE
     * market that {@link LendingMarketService#requiresOnchainBorrowPause} flags and whose
     * {@code borrowPaused} flag reads false, submits {@code setBorrowPaused(true)} through the durable outbox
     * ({@code SUBMITTED}). An unreadable chain is reported ({@code NOT_CHECKED}), never guessed. Only a pause is
     * ever submitted here; lifting it stays the 4-eyes {@code borrow-paused} action.
     */
    public List<PauseEnforcement> enforceLegacyBorrowPause(UUID actorId) {
        releaseGate.requireReleased();
        List<PauseEnforcement> results = new java.util.ArrayList<>();
        for (LendingMarket market : marketRepository.findByStatus(LendingMarketStatus.ACTIVE)) {
            if (!marketService.requiresOnchainBorrowPause(market)) continue;
            boolean paused;
            try {
                paused = onchainReader.borrowPaused(
                        marketService.resolveChainIdentifier(market.getChainConfigId()), market.getMarketAddress());
            } catch (RuntimeException e) {
                log.warn("Legacy-pause enforcement could not read borrowPaused of market {}: {}",
                        market.getMarketAddress(), e.getMessage());
                results.add(new PauseEnforcement(market.getId(), market.getMarketAddress(), "NOT_CHECKED", null));
                continue;
            }
            if (paused) {
                results.add(new PauseEnforcement(market.getId(), market.getMarketAddress(), "ALREADY_PAUSED", null));
                continue;
            }
            String txHash = setBorrowPaused(market.getId(), true,
                    "Legacy or unverified market: new borrowing disabled on-chain (withdraw/repay only)", actorId);
            results.add(new PauseEnforcement(market.getId(), market.getMarketAddress(), "SUBMITTED", txHash));
        }
        return results;
    }

    static byte[] hexToBytes32(String hex) {
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 + 2 * i, 4 + 2 * i), 16);
        }
        return out;
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 1000));
    }
}
