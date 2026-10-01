package de.makibytes.registerwerk.customer.events;

import java.util.List;
import java.util.UUID;

/**
 * Risk-relevant master data of an entity changed (name, LEI, country of registration): other
 * modules re-run their checks, e.g. {@code screening} re-screens the entity with trigger
 * {@code ENTITY_DATA_CHANGED}. Not an audit event itself - the update/rename audit event carries
 * the before/after values.
 */
public record EntityRiskDataChangedEvent(UUID entityId, UUID actorId, List<String> changedFields) {}
