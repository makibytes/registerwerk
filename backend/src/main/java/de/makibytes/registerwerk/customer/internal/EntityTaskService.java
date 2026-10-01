package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.api.EntityTaskRepository;
import de.makibytes.registerwerk.customer.events.EntityTaskCompletedEvent;
import de.makibytes.registerwerk.customer.events.EntityTaskOpenedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operator work items of the entity lifecycle (6-22, 6-23, 6-24). Opening is idempotent per
 * (entity, kind, ref) while OPEN; completing records who and why. Two gauges make open items
 * alert-able: {@code registerwerk_offboarding_open_tasks} (termination follow-ups, which stay
 * open after the entity is CLOSED) and {@code registerwerk_entity_review_open_tasks} (re-KYC and
 * chain-reinstatement requests).
 */
@Service
public class EntityTaskService implements EntityTaskPort {

    private static final Logger log = LoggerFactory.getLogger(EntityTaskService.class);

    /** Kinds that are not termination follow-ups. */
    static final List<String> REVIEW_KINDS = List.of(
            EntityTask.KYC_REVIEW_REQUIRED, EntityTask.CHAIN_REINSTATEMENT_REQUIRED, EntityTask.SPERRVERMERK_EXPIRY_REVIEW);

    private final EntityTaskRepository repository;
    private final ApplicationEventPublisher events;

    public EntityTaskService(EntityTaskRepository repository, ApplicationEventPublisher events,
                             MeterRegistry meterRegistry) {
        this.repository = repository;
        this.events = events;
        Gauge.builder("registerwerk_offboarding_open_tasks", repository,
                        r -> (double) r.countByStatusAndKindNotIn(EntityTask.Status.OPEN, REVIEW_KINDS))
                .description("Open follow-up tasks of terminated or merged customers (issued securities, "
                        + "Sperrvermerk holdings, open trades/positions); remain until an operator marks them DONE")
                .register(meterRegistry);
        Gauge.builder("registerwerk_entity_review_open_tasks", repository,
                        r -> (double) (r.countByStatus(EntityTask.Status.OPEN)
                                - r.countByStatusAndKindNotIn(EntityTask.Status.OPEN, REVIEW_KINDS)))
                .description("Open re-KYC / chain-reinstatement tasks raised by entity changes")
                .register(meterRegistry);
    }

    @Override
    @Transactional
    public boolean open(UUID entityId, String kind, String refId, String detail, UUID actorId) {
        String ref = refId == null ? "" : refId;
        if (repository.findByEntityIdAndKindAndRefIdAndStatus(entityId, kind, ref, EntityTask.Status.OPEN).isPresent()) {
            return false;
        }
        EntityTask task = new EntityTask();
        task.setEntityId(entityId);
        task.setKind(kind);
        task.setRefId(ref);
        task.setDetail(detail);
        task.setCreatedBy(actorId);
        EntityTask saved = repository.save(task);
        events.publishEvent(new EntityTaskOpenedEvent(entityId, actorId, saved.getId(), kind, ref, detail));
        log.warn("Entity task opened: entity={} kind={} ref={}", entityId, kind, ref);
        return true;
    }

    @Transactional(readOnly = true)
    public List<EntityTask> listOpen() {
        return repository.findByStatusOrderByCreatedAtAsc(EntityTask.Status.OPEN);
    }

    @Transactional(readOnly = true)
    public List<EntityTask> listForEntity(UUID entityId) {
        return repository.findByEntityIdOrderByCreatedAtDesc(entityId);
    }

    @Transactional
    public EntityTask complete(UUID taskId, UUID operatorId, String note) {
        EntityTask task = repository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("EntityTask", taskId));
        if (task.getStatus() == EntityTask.Status.DONE) {
            throw new IllegalStateException("Task " + taskId + " is already done");
        }
        task.setStatus(EntityTask.Status.DONE);
        task.setDoneAt(Instant.now());
        task.setDoneBy(operatorId);
        task.setDoneNote(note);
        EntityTask saved = repository.save(task);
        events.publishEvent(new EntityTaskCompletedEvent(
                task.getEntityId(), operatorId, "REGISTRY_ADMIN", taskId, task.getKind(), note));
        return saved;
    }
}
