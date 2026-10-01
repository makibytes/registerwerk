package de.makibytes.registerwerk.customer.api;

import java.util.UUID;

/**
 * Raises an operator work item for a legal entity. Idempotent: a second call for the same
 * (entity, kind, ref) while one is still OPEN returns without creating another. Implemented by
 * {@code customer}; other modules call it instead of logging a warning nobody reads.
 */
public interface EntityTaskPort {

    /** @return true if a new task was created, false if an identical one was already OPEN */
    boolean open(UUID entityId, String kind, String refId, String detail, UUID actorId);
}
