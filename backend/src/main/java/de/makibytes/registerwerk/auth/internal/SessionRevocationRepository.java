package de.makibytes.registerwerk.auth.internal;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SessionRevocationRepository extends JpaRepository<SessionRevocation, String> {

    @Modifying
    @Query("delete from SessionRevocation r where r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
