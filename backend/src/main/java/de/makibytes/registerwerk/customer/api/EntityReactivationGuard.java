package de.makibytes.registerwerk.customer.api;

import java.util.List;
import java.util.UUID;

/**
 * SPI: reasons why a SUSPENDED entity must not be reactivated yet (unresolved screening hit,
 * active Sperrvermerk, expired/rejected KYC). Implemented by the owning modules; an empty list
 * means "no objection". Fail closed: an implementation that cannot decide reports a blocker.
 */
public interface EntityReactivationGuard {

    List<String> blockers(UUID entityId);
}
