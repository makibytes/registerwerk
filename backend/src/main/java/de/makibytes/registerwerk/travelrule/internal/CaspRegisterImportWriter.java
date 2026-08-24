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

    public CaspRegisterImportWriter(CaspRegistryService registryService,
                                    CaspAuthorizationRepository repository,
                                    ApplicationEventPublisher eventPublisher) {
        this.registryService = registryService;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean upsert(CaspAuthorization entry, UUID actorId, String actorRole) {
        boolean existed = repository.findByVaspDidIgnoreCase(entry.getVaspDid()).isPresent();
        registryService.upsert(entry, actorId, actorRole);
        return existed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCompleted(String source, int created, int updated, int failed,
                                UUID actorId, String actorRole) {
        eventPublisher.publishEvent(new CaspRegisterImportedEvent(
                UUID.randomUUID(), actorId, actorRole, Map.of(
                        "source", source,
                        "created", created,
                        "updated", updated,
                        "failed", failed)));
    }
}
