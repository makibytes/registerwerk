package de.makibytes.registerwerk.customer.api;

import java.util.UUID;

/**
 * Whether a legal entity still has active register holdings (T3-14): while it does, GDPR erasure
 * of its contact data collides with the statutory §19 eWpG notice duty and the operator must name
 * the notice channel that is retained. Implemented outside {@code customer} (the register owner).
 */
public interface ActiveHoldingsPort {

    boolean hasActiveHoldings(UUID entityId);
}
