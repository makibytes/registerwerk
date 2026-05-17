package de.makibytes.registerwerk.customer;

import de.makibytes.registerwerk.customer.api.LegalEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * Public API for the customer module.
 */
public interface CustomerApi {

    Optional<LegalEntity> findLegalEntity(UUID id);

    void activateLegalEntity(UUID id);

    String nextEntityNumber();
}
