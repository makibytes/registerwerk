package de.makibytes.registerwerk.customer.api;

import java.util.List;
import java.util.UUID;

/**
 * SPI: a module that owns state a departing customer may still be bound to (issued securities,
 * open trades, repo/lending positions, pending corporate actions, register transfers,
 * Sperrvermerk holdings) reports it here. {@code customer} cannot depend on those modules, so
 * each implements this interface in its own package and {@code CustomerOffboardingService}
 * collects all beans. Implementations must be read-only and cheap.
 */
public interface OffboardingObligationSource {

    List<OffboardingObligation> openObligations(UUID entityId);
}
