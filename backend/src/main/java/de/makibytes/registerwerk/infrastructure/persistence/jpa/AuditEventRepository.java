package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.makibytes.registerwerk.domain.audit.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findBySubjectTypeAndSubjectId(String subjectType, UUID subjectId, Pageable pageable);

    Page<AuditEvent> findByEventType(String eventType, Pageable pageable);

    Page<AuditEvent> findByActorId(UUID actorId, Pageable pageable);

    @Query(
        value = """
            select *
            from audit_event e
            where e.event_type = 'KYC_JURISDICTION_APPROVED'
              and e.payload ->> 'overrideApplied' = 'true'
              and (:jurisdiction is null or e.payload ->> 'jurisdiction' = :jurisdiction)
              and (cast(:fromTs as timestamptz) is null or e.occurred_at >= cast(:fromTs as timestamptz))
              and (cast(:toTs as timestamptz) is null or e.occurred_at <= cast(:toTs as timestamptz))
            order by e.occurred_at desc
            """,
        countQuery = """
            select count(*)
            from audit_event e
            where e.event_type = 'KYC_JURISDICTION_APPROVED'
              and e.payload ->> 'overrideApplied' = 'true'
              and (:jurisdiction is null or e.payload ->> 'jurisdiction' = :jurisdiction)
              and (cast(:fromTs as timestamptz) is null or e.occurred_at >= cast(:fromTs as timestamptz))
              and (cast(:toTs as timestamptz) is null or e.occurred_at <= cast(:toTs as timestamptz))
            """,
        nativeQuery = true
    )
    Page<AuditEvent> findKycOverrideApprovals(
        @Param("jurisdiction") String jurisdiction,
        @Param("fromTs") Instant fromTs,
        @Param("toTs") Instant toTs,
        Pageable pageable);
}
