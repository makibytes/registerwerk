package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionNote;
import de.makibytes.registerwerk.trading.api.TradeExecutionNoteRepository;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.events.TradeNoteAddedEvent;
import de.makibytes.registerwerk.trading.web.dto.TimeoutBacklogResponse;
import de.makibytes.registerwerk.trading.web.dto.TradeNoteResponse;
import de.makibytes.registerwerk.trading.web.dto.UnresolvedTradeResponse;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the PAYMENT_UNRESOLVED workflow (Phase 5, 5A-03): the operator queue, the evidence
 * notes both parties may add, the rollout backlog report (SRE condition of the panel) and the
 * gauges that make an ageing queue alertable.
 */
@Service
@Transactional
public class TradeQueueService {

    private static final Logger log = LoggerFactory.getLogger(TradeQueueService.class);

    private final TradeExecutionRepository executionRepository;
    private final TradeExecutionNoteRepository noteRepository;
    private final TradingProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public TradeQueueService(TradeExecutionRepository executionRepository, TradeExecutionNoteRepository noteRepository,
                             TradingProperties properties, ApplicationEventPublisher eventPublisher,
                             MeterRegistry meterRegistry) {
        this.executionRepository = executionRepository;
        this.noteRepository = noteRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        Gauge.builder("registerwerk.trading.unresolved.count", executionRepository,
                        r -> r.countBySettlementStatus(SettlementStatus.PAYMENT_UNRESOLVED))
                .description("Trades with a declared payment waiting for an operator decision (PAYMENT_UNRESOLVED)")
                .register(meterRegistry);
        Gauge.builder("registerwerk.trading.unresolved.aged.count", executionRepository,
                        r -> r.countBySettlementStatusAndUnresolvedAtBefore(SettlementStatus.PAYMENT_UNRESOLVED, agedCutoff()))
                .description("PAYMENT_UNRESOLVED trades older than registerwerk.trading.unresolved-alert-hours")
                .register(meterRegistry);
        Gauge.builder("registerwerk.trading.timeout.backlog", executionRepository,
                        r -> r.countBySettlementStatusAndPaymentDeclaredAtBefore(
                                SettlementStatus.AWAITING_SELLER_CONFIRMATION, timeoutCutoff()))
                .description("Declared-payment trades already past the timeout that the next job run moves to PAYMENT_UNRESOLVED")
                .register(meterRegistry);
    }

    @Transactional(readOnly = true)
    public List<UnresolvedTradeResponse> listUnresolved() {
        Instant now = Instant.now();
        Instant aged = agedCutoff();
        return executionRepository.findBySettlementStatusOrderByUnresolvedAtAsc(SettlementStatus.PAYMENT_UNRESOLVED).stream()
                .map(e -> new UnresolvedTradeResponse(
                        TradeResponses.execution(null, e),
                        e.getBuyerEntityId(),
                        e.getSellerEntityId(),
                        e.getUnresolvedAt() == null ? 0 : Duration.between(e.getUnresolvedAt(), now).toHours(),
                        e.getUnresolvedAt() != null && e.getUnresolvedAt().isBefore(aged),
                        notesOf(e.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TimeoutBacklogResponse backlog() {
        Instant cutoff = timeoutCutoff();
        return new TimeoutBacklogResponse(
                executionRepository.findIdsBySettlementStatusAndCreatedAtBefore(SettlementStatus.PENDING, cutoff).size(),
                executionRepository.countBySettlementStatusAndPaymentDeclaredAtBefore(
                        SettlementStatus.AWAITING_SELLER_CONFIRMATION, cutoff),
                executionRepository.countBySettlementStatus(SettlementStatus.PAYMENT_UNRESOLVED),
                executionRepository.countBySettlementStatusAndUnresolvedAtBefore(SettlementStatus.PAYMENT_UNRESOLVED, agedCutoff()),
                executionRepository.findFailedAfterDeclaredPayment().stream().map(TradeExecution::getId).toList());
    }

    /** Visible right after a deploy, i.e. BEFORE the hourly job's first run moves anything. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void logBacklogAtStartup() {
        try {
            logBacklog(timeoutCutoff());
        } catch (RuntimeException e) {
            log.warn("Trading backlog report at start-up failed: {}", e.toString());
        }
    }

    /** Logged once before the first timeout run of a process (and on demand). */
    @Transactional(readOnly = true)
    public void logBacklog(Instant cutoff) {
        long overdueAwaiting = executionRepository.countBySettlementStatusAndPaymentDeclaredAtBefore(
                SettlementStatus.AWAITING_SELLER_CONFIRMATION, cutoff);
        List<UUID> failedDeclared = executionRepository.findFailedAfterDeclaredPayment().stream()
                .map(TradeExecution::getId).toList();
        if (overdueAwaiting > 0 || !failedDeclared.isEmpty()) {
            log.warn("TRADING BACKLOG before first timeout run: {} declared-payment trade(s) past the timeout will move to "
                    + "PAYMENT_UNRESOLVED and appear in the operator queue (GET /api/v1/admin/trading/unresolved); "
                    + "{} historic FAILED trade(s) had a declared payment and are listed for review only: {}",
                    overdueAwaiting, failedDeclared.size(), failedDeclared);
        }
    }

    @Transactional(readOnly = true)
    public void warnAgedUnresolved() {
        long aged = executionRepository.countBySettlementStatusAndUnresolvedAtBefore(
                SettlementStatus.PAYMENT_UNRESOLVED, agedCutoff());
        if (aged > 0) {
            log.warn("TRADING ALERT: {} PAYMENT_UNRESOLVED trade(s) older than {}h await an operator decision.",
                    aged, properties.getUnresolvedAlertHours());
        }
    }

    /** Adds a text note; {@code entityId == null} means an operator (REGISTRY_ADMIN) note. */
    public TradeNoteResponse addNote(UUID entityId, UUID actorId, String actorRole, UUID executionId, String text) {
        TradeExecution execution = requireVisible(entityId, executionId);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A note must not be empty");
        }
        TradeExecutionNote note = new TradeExecutionNote();
        note.setExecutionId(execution.getId());
        note.setActorEntityId(entityId);
        note.setActorUserId(actorId);
        note.setActorRole(actorRole);
        note.setText(text.strip());
        TradeExecutionNote saved = noteRepository.save(note);
        eventPublisher.publishEvent(new TradeNoteAddedEvent(execution.getId(), actorId, actorRole, saved.getId()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<TradeNoteResponse> listNotes(UUID entityId, UUID executionId) {
        requireVisible(entityId, executionId);
        return notesOf(executionId);
    }

    private TradeExecution requireVisible(UUID entityId, UUID executionId) {
        TradeExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new EntityNotFoundException("TradeExecution", executionId));
        if (entityId != null && !entityId.equals(execution.getBuyerEntityId()) && !entityId.equals(execution.getSellerEntityId())) {
            throw new AccessDeniedException("Only the buyer or seller of this trade may use its notes");
        }
        return execution;
    }

    private List<TradeNoteResponse> notesOf(UUID executionId) {
        return noteRepository.findByExecutionIdOrderByCreatedAtAsc(executionId).stream().map(this::toResponse).toList();
    }

    private TradeNoteResponse toResponse(TradeExecutionNote n) {
        return new TradeNoteResponse(n.getId(), n.getActorRole(), n.getActorEntityId(), n.getText(), n.getCreatedAt());
    }

    private Instant agedCutoff() {
        return Instant.now().minus(properties.getUnresolvedAlertHours(), ChronoUnit.HOURS);
    }

    private Instant timeoutCutoff() {
        return Instant.now().minus(properties.getPendingTimeoutHours(), ChronoUnit.HOURS);
    }
}
