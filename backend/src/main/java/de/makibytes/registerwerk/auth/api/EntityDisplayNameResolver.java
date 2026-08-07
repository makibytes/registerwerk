package de.makibytes.registerwerk.auth.api;

import java.util.UUID;

/**
 * Resolves a legal entity's display name for session/impersonation responses.
 *
 * <p>Defined here rather than called directly against {@code customer.api.LegalEntityRepository}
 * so that {@code auth} does not depend on {@code customer} — {@code customer} already legitimately
 * depends on {@code auth} (it manages company users, which are {@link AppUser} principals), and a
 * dependency in the other direction would create a module cycle. {@code customer.internal}
 * provides the implementation; Spring wires it by interface, so this stays a one-way,
 * compile-time dependency from {@code customer} to {@code auth} only.
 */
public interface EntityDisplayNameResolver {

    /** The entity's current display name, or null if the entity doesn't exist. */
    String resolveName(UUID entityId);
}
