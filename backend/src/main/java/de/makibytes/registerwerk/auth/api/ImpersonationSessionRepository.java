package de.makibytes.registerwerk.auth.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImpersonationSessionRepository extends JpaRepository<ImpersonationSession, UUID> {

    Optional<ImpersonationSession> findByHandoffCodeHash(String handoffCodeHash);

    /** Atomic single-use: returns 1 only for the one caller that consumes a live, unconsumed code. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ImpersonationSession s set s.handoffConsumedAt = :now "
            + "where s.handoffCodeHash = :hash and s.handoffConsumedAt is null "
            + "and s.handoffExpiresAt > :now and s.endedAt is null and s.expiresAt > :now")
    int consumeHandoff(@Param("hash") String hash, @Param("now") Instant now);

    List<ImpersonationSession> findByEndedAtIsNullAndExpiresAtBefore(Instant now);

    List<ImpersonationSession> findByTargetEntityIdOrderByStartedAtDesc(UUID targetEntityId, Pageable page);
}
