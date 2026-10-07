package de.makibytes.registerwerk.indexer.api;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demo-mode stand-in for {@code registerwerk.sync.nominee-pool-entity-id}: the legal entity that
 * nominee-pool holder rows (lending markets etc.) are held in. Only the demo seeder
 * ({@code bootstrap.DemoNomineePoolSeeder}, a {@code DemoOnly} bean that the production readiness
 * check refuses) ever sets it, so in production this stays empty and the configured property
 * remains the only source — an unset property keeps its ERROR and the asset's holder sync is
 * BLOCKED until a pool is registered by hand.
 */
@Component
public class DemoNomineePoolEntity {

    private final AtomicReference<UUID> entityId = new AtomicReference<>();

    /** Called by the demo seeder once the demo nominee-pool legal entity exists. */
    public void set(UUID legalEntityId) {
        entityId.set(legalEntityId);
    }

    public Optional<UUID> get() {
        return Optional.ofNullable(entityId.get());
    }
}
