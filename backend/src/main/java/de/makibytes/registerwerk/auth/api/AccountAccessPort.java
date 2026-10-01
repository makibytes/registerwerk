package de.makibytes.registerwerk.auth.api;

import java.util.UUID;

/**
 * The one sanctioned way for other modules (access review today) to withdraw an account's access.
 * It applies the same guards as the operator / company user-management screens, so a bulk lever
 * such as a recertification campaign cannot lock the platform out of its last administrator.
 */
public interface AccountAccessPort {

    /**
     * Disables the account, revokes its sessions and burns its unconsumed registration / reset
     * tokens, and publishes a user-lifecycle audit event that carries the account's roles.
     *
     * @throws IllegalArgumentException   when the actor targets their own account
     * @throws de.makibytes.registerwerk.shared.InvalidStateTransitionException when the account is
     *         the last enabled REGISTRY_ADMIN, or the last enabled COMPANY_ADMIN of its entity
     * @throws de.makibytes.registerwerk.shared.EntityNotFoundException when the account does not exist
     */
    void disable(UUID userId, UUID actorId, String actorRole, String reason);
}
