package de.makibytes.registerwerk.idempotency.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.entityId = :entityId AND r.idempotencyKey = :key")
    Optional<IdempotencyRecord> findForUpdate(@Param("entityId") UUID entityId, @Param("key") String key);

    Optional<IdempotencyRecord> findByEntityIdAndIdempotencyKey(UUID entityId, String idempotencyKey);

    /** Input for {@code IdempotencyCleanupJob} — records older than the retention window,
     *  regardless of status (a crashed IN_PROGRESS row must not block its key forever). */
    List<IdempotencyRecord> findByCreatedAtBefore(Instant cutoff);
}
