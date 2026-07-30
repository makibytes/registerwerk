package de.makibytes.registerwerk.admin.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.makibytes.registerwerk.admin.events.OperatorEntraMethodDeletedEvent;
import de.makibytes.registerwerk.admin.events.OperatorEntraMfaResetEvent;
import de.makibytes.registerwerk.admin.events.OperatorEntraSessionsRevokedEvent;
import de.makibytes.registerwerk.admin.events.OperatorEntraTapIssuedEvent;
import de.makibytes.registerwerk.admin.web.dto.EntraAuthMethodDto;
import de.makibytes.registerwerk.admin.web.dto.EntraMethodsResponse;
import de.makibytes.registerwerk.admin.web.dto.EntraResetOutcomeResponse;
import de.makibytes.registerwerk.admin.web.dto.TemporaryAccessPassRequest;
import de.makibytes.registerwerk.admin.web.dto.TemporaryAccessPassResponse;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.entra.api.EntraAuthMethod;
import de.makibytes.registerwerk.entra.api.EntraAuthMethodType;
import de.makibytes.registerwerk.entra.api.EntraDirectoryPort;
import de.makibytes.registerwerk.entra.api.EntraIdentityGate;
import de.makibytes.registerwerk.entra.api.EntraIdentityModel;
import de.makibytes.registerwerk.entra.api.EntraUnsupportedForIdentityModelException;
import de.makibytes.registerwerk.entra.api.RegisterwerkEntraProperties;
import de.makibytes.registerwerk.entra.api.TemporaryAccessPass;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-side support for customers who cannot complete two-factor authentication — in
 * practice, almost always a lost or replaced phone.
 *
 * <p>The flow it implements is Microsoft's documented one, and the order matters:
 * <ol>
 *   <li>list the registered methods, so the operator can see what they are removing;</li>
 *   <li>delete them, which is the only way to force re-registration — Graph has no "reset MFA"
 *       call;</li>
 *   <li><strong>revoke sign-in sessions</strong>, because neither a password reset nor deleting
 *       methods invalidates existing refresh tokens or browser cookies;</li>
 *   <li>issue a short-lived Temporary Access Pass and hand it over out-of-band.</li>
 * </ol>
 *
 * <p>The real security boundary is the out-of-band identity check the operator performs before
 * any of this. Everything here is mechanism: hence step-up on every mutation, and 4-eyes on the
 * two that can result in someone else signing in as the customer.
 */
@Service
public class EntraSupportService {

    private static final Logger log = LoggerFactory.getLogger(EntraSupportService.class);

    private final EntraDirectoryPort directory;
    private final EntraIdentityGate identityGate;
    private final AppUserRepository appUserRepository;
    private final RegisterwerkEntraProperties entraProperties;
    private final ApplicationEventPublisher eventPublisher;

    EntraSupportService(
            EntraDirectoryPort directory,
            EntraIdentityGate identityGate,
            AppUserRepository appUserRepository,
            RegisterwerkEntraProperties entraProperties,
            ApplicationEventPublisher eventPublisher) {
        this.directory = directory;
        this.identityGate = identityGate;
        this.appUserRepository = appUserRepository;
        this.entraProperties = entraProperties;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public EntraMethodsResponse listMethods(UUID userId) {
        AppUser user = requireUser(userId);
        EntraIdentityModel model = identityGate.classify(user);

        if (!model.isManagedHere()) {
            return new EntraMethodsResponse(model.name(), false, false, false, List.of(),
                    messageFor(model, user));
        }
        if (!directory.isEnabled() || user.getEntraObjectId() == null) {
            return new EntraMethodsResponse(model.name(), false, false, false, List.of(),
                    "The Microsoft Graph integration is not configured, so authentication methods "
                    + "cannot be read or changed from here.");
        }

        String oid = user.getEntraObjectId().toString();
        List<EntraAuthMethod> methods = directory.listAuthMethods(oid);
        boolean registered = methods.stream().anyMatch(m -> m.type().isSecondFactor());

        return new EntraMethodsResponse(
                model.name(),
                true,
                identityGate.supportsTemporaryAccessPass(user),
                registered,
                methods.stream().map(EntraSupportService::toDto).toList(),
                null);
    }

    @Transactional
    public void deleteMethod(Authentication actor, UUID userId, EntraAuthMethodType type, String methodId) {
        AppUser user = requireManageable(userId);
        directory.deleteAuthMethod(user.getEntraObjectId().toString(), type, methodId);

        eventPublisher.publishEvent(new OperatorEntraMethodDeletedEvent(
                user.getId(), SecurityUtils.extractUserId(actor),
                SecurityUtils.primaryRole(actor, "REGISTRY_ADMIN"),
                Map.of("methodType", type.name(), "methodId", methodId, "targetEmail", user.getEmail())));
    }

    @Transactional
    public EntraResetOutcomeResponse resetAllMethods(Authentication actor, UUID userId, UUID approverId) {
        AppUser user = requireManageable(userId);
        EntraDirectoryPort.ResetOutcome outcome =
                directory.resetAllAuthMethods(user.getEntraObjectId().toString());

        // A reset invalidates the user's ability to complete MFA but not their existing sessions;
        // the operator is expected to revoke separately, and the console prompts for it.
        user.setEntraMfaRegisteredAt(null);
        user.setEntraMfaCheckedAt(null);
        appUserRepository.save(user);

        eventPublisher.publishEvent(new OperatorEntraMfaResetEvent(
                user.getId(), SecurityUtils.extractUserId(actor),
                SecurityUtils.primaryRole(actor, "REGISTRY_ADMIN"),
                Map.of(
                        "targetEmail", user.getEmail(),
                        "deletedCount", outcome.deleted().size(),
                        "deletedTypes", outcome.deleted().stream().map(m -> m.type().name()).toList(),
                        "failures", outcome.failures(),
                        "complete", outcome.complete(),
                        "dualControlApproverId", String.valueOf(approverId))));

        return new EntraResetOutcomeResponse(
                outcome.complete(),
                outcome.deleted().stream().map(EntraSupportService::describe).toList(),
                outcome.failures());
    }

    @Transactional
    public void revokeSessions(Authentication actor, UUID userId) {
        AppUser user = requireManageable(userId);
        directory.revokeSignInSessions(user.getEntraObjectId().toString());

        eventPublisher.publishEvent(new OperatorEntraSessionsRevokedEvent(
                user.getId(), SecurityUtils.extractUserId(actor),
                SecurityUtils.primaryRole(actor, "REGISTRY_ADMIN"),
                Map.of("targetEmail", user.getEmail())));
    }

    /**
     * Issues a Temporary Access Pass. The returned value is the only copy — it is not persisted,
     * not logged, and not put into the audit payload.
     */
    @Transactional
    public TemporaryAccessPassResponse issueTemporaryAccessPass(
            Authentication actor, UUID userId, TemporaryAccessPassRequest request, UUID approverId) {

        AppUser user = requireManageable(userId);
        if (!identityGate.supportsTemporaryAccessPass(user)) {
            // Refused before calling Graph: Graph's own error for this case is opaque, and the
            // operator needs to be told what to do instead.
            throw new EntraUnsupportedForIdentityModelException(
                    "A Temporary Access Pass cannot be issued to an external guest account. "
                    + "Remove their authentication methods and have them re-register through "
                    + "their home organisation instead.",
                    identityGate.classify(user));
        }

        int lifetime = request.lifetimeMinutes() > 0
                ? request.lifetimeMinutes()
                : entraProperties.getTap().getDefaultLifetimeMinutes();

        TemporaryAccessPass tap = directory.issueTemporaryAccessPass(
                user.getEntraObjectId().toString(), lifetime, request.usableOnce());

        eventPublisher.publishEvent(new OperatorEntraTapIssuedEvent(
                user.getId(), SecurityUtils.extractUserId(actor),
                SecurityUtils.primaryRole(actor, "REGISTRY_ADMIN"),
                // Note the absence of tap.value(). See OperatorEntraTapIssuedEvent's Javadoc.
                Map.of(
                        "targetEmail", user.getEmail(),
                        "tapId", String.valueOf(tap.id()),
                        "lifetimeMinutes", tap.lifetimeMinutes(),
                        "usableOnce", tap.usableOnce(),
                        "expiresAt", String.valueOf(tap.expiresAt()),
                        "dualControlApproverId", String.valueOf(approverId))));

        log.info("Temporary Access Pass issued by {} for user {} (lifetime {} min)",
                SecurityUtils.extractUserId(actor), user.getId(), tap.lifetimeMinutes());

        return new TemporaryAccessPassResponse(
                tap.id(), tap.value(), tap.startAt(), tap.expiresAt(),
                tap.lifetimeMinutes(), tap.usableOnce());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AppUser requireUser(UUID userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
    }

    /**
     * Resolves a user and refuses, before any Graph call, anyone whose methods we cannot manage.
     * Reaching Graph for a federated user would return a confusing 404 for a principal that
     * genuinely does not exist in our tenant.
     */
    private AppUser requireManageable(UUID userId) {
        AppUser user = requireUser(userId);
        EntraIdentityModel model = identityGate.classify(user);
        if (!model.isManagedHere()) {
            throw new EntraUnsupportedForIdentityModelException(messageFor(model, user), model);
        }
        if (user.getEntraObjectId() == null) {
            throw new EntraUnsupportedForIdentityModelException(
                    "This account has never signed in through Microsoft Entra ID, so it has no "
                    + "directory identity to manage yet.", model);
        }
        return user;
    }

    private static String messageFor(EntraIdentityModel model, AppUser user) {
        return switch (model) {
            case FEDERATED -> "Identity is federated to tenant " + user.getEntraTenantId()
                    + ". Authentication methods are managed by the customer's own Microsoft Entra "
                    + "tenant — contact their administrator.";
            case LOCAL -> user.getAuthProvider() == UserAuthProvider.LOCAL
                    ? "This is a local account. Use the password-reset action instead."
                    : "Microsoft Entra ID sign-in is not enabled in this environment.";
            default -> null;
        };
    }

    private static EntraAuthMethodDto toDto(EntraAuthMethod method) {
        return new EntraAuthMethodDto(
                method.id(),
                method.type().name(),
                describe(method),
                method.isDefault(),
                method.type().isDeletable(),
                method.createdAt());
    }

    private static String describe(EntraAuthMethod method) {
        return method.displayName() == null || method.displayName().isBlank()
                ? method.type().name()
                : method.type().name() + " (" + method.displayName() + ")";
    }
}
