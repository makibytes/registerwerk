package de.makibytes.registerwerk.auth.api;

import java.util.UUID;

/**
 * Lets the session guard ask whether a customer entity is still in service without {@code auth}
 * depending on {@code customer} (same reasoning as {@link EntityDisplayNameResolver}).
 */
public interface EntityActivityPort {

    /** True when the entity is CLOSED or DISSOLVED (sessions bound to it must end). Unknown ids: false. */
    boolean isTerminated(UUID entityId);

    /**
     * Entra tenant the entity federates from ({@code idpTenantId}); empty when none is configured
     * or the entity is unknown. Lets identity linking accept a customer-tenant token for that
     * entity's users without {@code auth} depending on {@code customer}.
     */
    default java.util.Optional<UUID> idpTenantOf(UUID entityId) {
        return java.util.Optional.empty();
    }
}
