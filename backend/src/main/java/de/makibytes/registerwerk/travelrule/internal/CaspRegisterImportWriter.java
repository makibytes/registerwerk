package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.events.CaspRegisterImportedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Isolates each bulk-import row in its own transaction. A database failure in one
 * row must not mark the remaining best-effort import rollback-only.
 */
@Component
public class CaspRegisterImportWriter {

    private final CaspRegistryService registryService;
    private final CaspAuthorizationRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public CaspRegisterImportWriter(CaspRegistryService registryService,
                                    CaspAuthorizationRepository repository,
                                    ApplicationEventPublisher eventPublisher,
                                    org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.registryService = registryService;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /** @param existed row already present; @param statusChanged its stored status differs from the incoming one */
    public record Outcome(boolean existed, boolean statusChanged) {}

    /** Preview classification (no write). Mirrors the lookup the upsert uses: DID, then LEI. */
    @Transactional(readOnly = true)
    public Outcome classify(CaspAuthorization entry) {
        var existing = repository.findByVaspDidIgnoreCase(entry.getVaspDid());
        if (existing.isEmpty() && entry.getLei() != null) {
            existing = repository.findByLeiIgnoreCase(entry.getLei());
        }
        return new Outcome(existing.isPresent(), existing.isPresent() && existing.get().getStatus() != entry.getStatus());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome upsert(CaspAuthorization entry, UUID actorId, String actorRole, UUID approverId) {
        Outcome outcome = classify(entry);
        registryService.upsert(entry, actorId, actorRole, approverId);
        return outcome;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCompleted(String source, int created, int updated, int statusChanged, int failed,
                                String digest, UUID actorId, String actorRole, UUID approverId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO casp_register_import (id, source, created, updated, status_changed, failed, diff_digest,
                                              actor_id, approver_id)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, id, source, created, updated, statusChanged, failed, digest, actorId, approverId);
        eventPublisher.publishEvent(new CaspRegisterImportedEvent(
                id, actorId, actorRole, Map.of(
                        "source", source,
                        "created", created,
                        "updated", updated,
                        "statusChanged", statusChanged,
                        "failed", failed,
                        "diffDigest", digest), approverId));
    }
}
