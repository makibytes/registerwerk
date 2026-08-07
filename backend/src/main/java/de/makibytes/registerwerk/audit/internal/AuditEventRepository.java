package de.makibytes.registerwerk.audit.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findBySubjectTypeAndSubjectId(String subjectType, UUID subjectId, Pageable pageable);

    Page<AuditEvent> findByEventType(String eventType, Pageable pageable);

    Page<AuditEvent> findByActorId(UUID actorId, Pageable pageable);

    /**
     * {@link Slice}, not {@link Page} — used by the nightly chain verification scan, which
     * only needs {@code isLast()} and never displays a total count. A {@code Page} return
     * type forces Spring Data to run an extra {@code COUNT(*)} query per batch; over a
     * multi-million-row table scanned nightly, that's thousands of wasted count queries.
     */
    Slice<AuditEvent> findAllSliceBy(Pageable pageable);

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

    /**
     * Backs the CSV/evidence export — the only downloads the portal offered before this were
     * per-customer PDFs, so compliance had no way to hand an auditor a data extract for a date
     * range or case without direct database access. Capped by the caller-supplied
     * {@link Pageable} (the controller enforces a hard max page size) rather than being truly
     * unbounded, since {@code audit_event} is partitioned and can be very large.
     */
    @Query(
        value = """
            select *
            from audit_event e
            where (cast(:subjectType as text) is null or e.subject_type = :subjectType)
              and (cast(:eventType as text) is null or e.event_type = :eventType)
              and (cast(:actorId as uuid) is null or e.actor_id = :actorId)
              and (cast(:fromTs as timestamptz) is null or e.occurred_at >= cast(:fromTs as timestamptz))
              and (cast(:toTs as timestamptz) is null or e.occurred_at <= cast(:toTs as timestamptz))
            order by e.occurred_at asc
            """,
        nativeQuery = true
    )
    List<AuditEvent> findForExport(
        @Param("subjectType") String subjectType,
        @Param("eventType") String eventType,
        @Param("actorId") UUID actorId,
        @Param("fromTs") Instant fromTs,
        @Param("toTs") Instant toTs,
        Pageable pageable);
}
