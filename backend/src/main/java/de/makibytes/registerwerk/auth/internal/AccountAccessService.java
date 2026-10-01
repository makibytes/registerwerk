package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AccountAccessPort;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenRepository;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.events.UserAccessRevokedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Disables an account with the same last-admin and self guards as the user-management screens.
 * {@link AppUser#setEnabled} bumps {@code tokens_valid_after}, so live sessions end with the
 * disable (6-01); unconsumed registration / reset tokens are burnt so the disable cannot be undone
 * through a public token (6-02).
 */
@Service
class AccountAccessService implements AccountAccessPort {

    /** Follow-ups a disable does NOT cascade to; listed for the operator in the audit payload (T6-15). */
    private static final List<String> MANUAL_FOLLOW_UPS = List.of(
            "pending four-eyes requests initiated by the user",
            "organisation member wallets and on-chain roles",
            "signer / approver keys");

    private final AppUserRepository users;
    private final AppUserActionTokenRepository actionTokens;
    private final ApplicationEventPublisher events;

    AccountAccessService(AppUserRepository users, AppUserActionTokenRepository actionTokens,
                         ApplicationEventPublisher events) {
        this.users = users;
        this.actionTokens = actionTokens;
        this.events = events;
    }

    // The guards run before anything is modified, so a refusal must not mark a caller's (access review)
    // transaction rollback-only: it persists the review state it recorded before the refusal.
    @Override
    @Transactional(noRollbackFor = {InvalidStateTransitionException.class, IllegalArgumentException.class})
    public void disable(UUID userId, UUID actorId, String actorRole, String reason) {
        AppUser user = users.findById(userId).orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
        if (actorId != null && actorId.equals(userId)) {
            throw new IllegalArgumentException("Cannot disable your own account");
        }
        if (user.isEnabled()) {
            if (user.hasRole(AppUserRole.REGISTRY_ADMIN)
                    && users.countEnabledUsersWithRole(AppUserRole.REGISTRY_ADMIN, userId) == 0) {
                throw new InvalidStateTransitionException("The system must keep at least one enabled REGISTRY_ADMIN");
            }
            if (user.hasRole(AppUserRole.COMPANY_ADMIN) && user.getLegalEntityId() != null
                    && users.countEnabledUsersByLegalEntityIdAndRole(
                            user.getLegalEntityId(), AppUserRole.COMPANY_ADMIN, userId) == 0) {
                throw new InvalidStateTransitionException("A company must keep at least one enabled COMPANY_ADMIN");
            }
        }
        user.setEnabled(false);
        users.save(user);
        actionTokens.invalidateAllForUser(userId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("email", user.getEmail());
        details.put("roles", List.copyOf(new TreeSet<>(user.getRoles().stream().map(Enum::name).toList())));
        details.put("legalEntityId", user.getLegalEntityId() == null ? "" : user.getLegalEntityId().toString());
        details.put("enabled", false);
        details.put("reason", reason);
        details.put("cascade", "NOT_AUTOMATED");
        details.put("manualFollowUps", MANUAL_FOLLOW_UPS);
        events.publishEvent(new UserAccessRevokedEvent(userId, actorId, actorRole, details));
    }
}
