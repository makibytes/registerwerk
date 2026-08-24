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

    IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
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
    Outcome checkOrStart(UUID entityId, String key, String requestHash) {
        Optional<IdempotencyRecord> existing = repository.findForUpdate(entityId, key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.getRequestHash().equals(requestHash)) {
                return new Outcome.Conflict(422, "Idempotency-Key '" + key + "' was already used with a different request");
            }
            if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                return new Outcome.Conflict(409, "A request with this Idempotency-Key is already being processed");
            }
            return new Outcome.Replay(record.getResponseStatus(), record.getResponseBody());
        }

        IdempotencyRecord created = new IdempotencyRecord();
        created.setEntityId(entityId);
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
     * implementations (e.g. Stripe's) follow.
     */
    @Transactional
    void complete(UUID recordId, int responseStatus, String responseBody) {
        IdempotencyRecord record = repository.findById(recordId).orElse(null);
        if (record == null) {
            log.warn("Idempotency record {} disappeared before completion — nothing to update.", recordId);
            return;
        }
        if (responseStatus >= 500) {
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
