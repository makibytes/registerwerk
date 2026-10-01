package de.makibytes.registerwerk.idempotency.internal;

import de.makibytes.registerwerk.idempotency.api.IdempotencyRecord;
import de.makibytes.registerwerk.idempotency.api.IdempotencyRecordRepository;
import de.makibytes.registerwerk.idempotency.api.IdempotencyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Core check-or-start / complete lifecycle behind {@link IdempotencyFilter}, split into its own
 * {@code @Transactional} service so the pessimistic row lock in
 * {@code IdempotencyRecordRepository.findForUpdate} actually participates in a transaction (a
 * servlet filter method itself is not transactional).
 */
@Service
class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository repository;
    private final java.time.Duration inProgressLease;
    private final io.micrometer.core.instrument.Counter reclaimed;

    IdempotencyService(IdempotencyRecordRepository repository) {
        this(repository, java.time.Duration.ofMinutes(15), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @org.springframework.beans.factory.annotation.Autowired
    IdempotencyService(IdempotencyRecordRepository repository,
                       @org.springframework.beans.factory.annotation.Value("${registerwerk.idempotency.in-progress-lease:PT15M}")
                       java.time.Duration inProgressLease,
                       io.micrometer.core.instrument.MeterRegistry registry) {
        this.repository = repository;
        this.inProgressLease = inProgressLease;
        this.reclaimed = io.micrometer.core.instrument.Counter.builder("registerwerk_idempotency_reclaimed_total")
                .description("IN_PROGRESS idempotency records older than the lease that were taken over").register(registry);
    }

    sealed interface Outcome {
        /** No prior record for this key — proceed with the request; {@code recordId} identifies
         *  the IN_PROGRESS row {@link #complete} must later update. */
        record Proceed(UUID recordId) implements Outcome {}
        /** A completed prior request with the same hash — replay this response verbatim. */
        record Replay(int status, String body) implements Outcome {}
        /** Either a concurrent duplicate still in flight, or the same key reused for a
         *  different request — both are client-facing errors, never a silent double-execute. */
        record Conflict(int httpStatus, String message) implements Outcome {}
    }

    @Transactional
    Outcome checkOrStart(String scope, UUID scopeId, String key, String requestHash) {
        Optional<IdempotencyRecord> existing = repository.findForUpdate(scope, scopeId, key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.getRequestHash().equals(requestHash)) {
                return new Outcome.Conflict(422, "Idempotency-Key '" + key + "' was already used with a different request");
            }
            if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                if (record.getCreatedAt().isAfter(Instant.now().minus(inProgressLease))) {
                    return new Outcome.Conflict(409, "A request with this Idempotency-Key is already being processed");
                }
                // 7A-07: the owner crashed (OOM, eviction) before completing; the lease is longer than any
                // handler. Take the key over under the row lock; the durable outbox key still guards chain submissions.
                log.warn("Reclaiming IN_PROGRESS idempotency record {} older than {}", record.getId(), inProgressLease);
                reclaimed.increment();
                repository.delete(record);
                repository.flush();
            } else {
                return new Outcome.Replay(record.getResponseStatus(), record.getResponseBody());
            }
        }

        IdempotencyRecord created = new IdempotencyRecord();
        created.setScope(scope);
        created.setEntityId(scopeId);
        created.setIdempotencyKey(key);
        created.setRequestHash(requestHash);
        try {
            IdempotencyRecord saved = repository.saveAndFlush(created);
            return new Outcome.Proceed(saved.getId());
        } catch (DataIntegrityViolationException e) {
            // Lost the race to a concurrent request with the same key — same client-facing
            // outcome as finding it already IN_PROGRESS above.
            return new Outcome.Conflict(409, "A request with this Idempotency-Key is already being processed");
        }
    }

    /**
     * Records the final response. A 5xx is deliberately NOT locked in — it's a server failure,
     * not a completed action the caller should be stuck replaying; deleting the row lets a retry
     * with the same key attempt the action fresh, matching the convention most idempotency-key
     * implementations (e.g. Stripe's) follow. The same holds for every 4xx (401/403 challenges, and business
     * refusals such as 408/409/429 or "already exists"/"asset is paused" that depend on state): nothing was
     * executed, and the operator fixes the state and clicks again with the same key (the frontends keep
     * the key on those statuses) - replaying the stored 409 would make the action permanently
     * un-retryable. (The "already in progress" 409 is produced by {@link #checkOrStart}, not here.) A
     * step-up challenge likewise executed nothing; the client retries the identical request
     * (same key) once it has stepped up. A durable chain submission made by a request that later
     * failed is protected separately by the key stored on its outbox row.
     */
    /** Releases the record without storing a response (secret-returning handlers, {@code @NoIdempotencyReplay}). */
    @Transactional
    void release(UUID recordId) {
        repository.findById(recordId).ifPresent(repository::delete);
    }

    @Transactional
    void complete(UUID recordId, int responseStatus, String responseBody) {
        IdempotencyRecord record = repository.findById(recordId).orElse(null);
        if (record == null) {
            log.warn("Idempotency record {} disappeared before completion — nothing to update.", recordId);
            return;
        }
        if (responseStatus >= 400) {
            repository.delete(record);
            return;
        }
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setResponseStatus(responseStatus);
        record.setResponseBody(responseBody);
        record.setCompletedAt(Instant.now());
        repository.save(record);
    }
}
