package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.customer.events.CompanyUserInvitedEvent;
import de.makibytes.registerwerk.customer.events.CompanyUserRolesUpdatedEvent;
import de.makibytes.registerwerk.customer.events.CompanyUserDisabledEvent;
import de.makibytes.registerwerk.customer.events.CompanyUserPasswordResetRequestedEvent;
import de.makibytes.registerwerk.customer.events.CompanyUserDeletedEvent;
import de.makibytes.registerwerk.customer.events.CompanyIdpSettingsUpdatedEvent;
import de.makibytes.registerwerk.customer.events.CompanyAdminBootstrappedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserActionToken;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenType;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenRepository;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.PrincipalResolver;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.web.dto.CompanyIdpSettingsRequest;
import de.makibytes.registerwerk.customer.web.dto.CompanyIdpSettingsResponse;
import de.makibytes.registerwerk.customer.web.dto.CompanyMeResponse;
import de.makibytes.registerwerk.customer.web.dto.CompanyUserResponse;
import de.makibytes.registerwerk.customer.web.dto.InviteCompanyUserRequest;
import de.makibytes.registerwerk.auth.api.PublicPasswordResetCompleteRequest;
import de.makibytes.registerwerk.auth.api.PublicUserActionTokenInfoResponse;
import de.makibytes.registerwerk.auth.api.PublicUserRegistrationCompleteRequest;
import de.makibytes.registerwerk.customer.web.dto.UpdateCompanyUserRolesRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class CompanyUserService {

    private static final Logger log = LoggerFactory.getLogger(CompanyUserService.class);
    private static final int TOKEN_BYTES = 36;
    /** Roles a company administrator may hand out; everything else (operator roles, RELATIONSHIP_MANAGER, DAPP_PUBLISHER) is operator-assigned. */
    private static final Set<AppUserRole> MANAGED_ROLES = Set.of(
        AppUserRole.COMPANY_ADMIN, AppUserRole.ISSUER, AppUserRole.INVESTOR, AppUserRole.TRADER);

    private final AppUserRepository appUserRepository;
    private final AppUserActionTokenRepository actionTokenRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final RegisterwerkAuthProperties authProperties;
    private final PrincipalResolver principalResolver;
    private final de.makibytes.registerwerk.shared.SecureLinkPort secureLinks;
    private final String customerFrontendUrl;
    private final long userActionTokenTtlHours;

    public CompanyUserService(
            AppUserRepository appUserRepository,
            AppUserActionTokenRepository actionTokenRepository,
            LegalEntityRepository legalEntityRepository,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher eventPublisher,
            RegisterwerkAuthProperties authProperties,
            PrincipalResolver principalResolver,
            de.makibytes.registerwerk.shared.SecureLinkPort secureLinks,
            @Value("${registerwerk.onboarding.frontend-url:http://localhost:44201}") String customerFrontendUrl,
            @Value("${registerwerk.onboarding.user-action-ttl-hours:48}") long userActionTokenTtlHours) {
        this.appUserRepository = appUserRepository;
        this.actionTokenRepository = actionTokenRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.authProperties = authProperties;
        this.principalResolver = principalResolver;
        this.secureLinks = secureLinks;
        this.customerFrontendUrl = customerFrontendUrl;
        this.userActionTokenTtlHours = userActionTokenTtlHours;
    }

    @Transactional(readOnly = true)
    public CompanyMeResponse getMyEntity(Authentication authentication) {
        LegalEntity entity = getCurrentEntity(authentication);
        return new CompanyMeResponse(
            entity.getId(),
            entity.getCurrentName(),
            entity.getRegistrationNumber(),
            entity.getRegistrationCountry(),
            entity.getType().name(),
            entity.getKycStatus().name(),
            null,
            entity.getStatus() != EntityStatus.PENDING_ONBOARDING,
            entity.getIdpIssuerUrl(),
            entity.getIdpClientId(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            null
        );
    }

    @Transactional(readOnly = true)
    public List<CompanyUserResponse> listUsers(Authentication authentication) {
        UUID entityId = requireEntityId(authentication);
        return appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId).stream()
            .map(this::toResponse)
            .toList();
    }

    /**
     * Records this sign-in against the caller's account, provisioning one on first sight of an
     * Entra principal.
     *
     * <p>Identity resolution and provisioning now live in {@code auth}'s {@link PrincipalResolver}
     * — this used to key off email alone and never recorded the Entra object id, which is what
     * left {@code app_user.id} and the token's {@code sub} as unrelated values. All this adds on
     * top is the last-login timestamp.
     */
    public void syncAuthenticatedPrincipal(Authentication authentication) {
        principalResolver.resolve(authentication).ifPresent(user -> {
            user.setLastLoginAt(Instant.now());
            appUserRepository.save(user);
        });
    }

    public CompanyUserResponse inviteUser(Authentication authentication, InviteCompanyUserRequest request) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();
        validateManagedRoles(request.roles());

        if (appUserRepository.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw new IllegalArgumentException("A user with this email already exists");
        }

        LegalEntity entity = getEntity(entityId);
        AppUser user = new AppUser();
        user.setEmail(request.email().trim());
        user.setFullName(request.name().trim());
        user.setLegalEntityId(entityId);
        user.setAuthProvider(UserAuthProvider.LOCAL);
        user.setEnabled(true);
        user.setCreatedBy(actorId);
        user.setRoles(request.roles());
        user.markRolesChanged(actorId);
        AppUser saved = appUserRepository.save(user);

        String registrationToken = createActionToken(saved, AppUserActionTokenType.REGISTRATION, actorId);
        eventPublisher.publishEvent(new CompanyUserInvitedEvent(entityId, saved.getId(), actorId, actorRole(authentication),
            saved.getEmail(), saved.getFullName(),
            secureLinks.seal(customerFrontendUrl + "/register/" + registrationToken, saved.getId())));

        // CompanyUserInvitedEvent already published above with notification data
        return toResponse(saved);
    }

    public CompanyUserResponse updateUserRoles(
            Authentication authentication,
            UUID userId,
            UpdateCompanyUserRolesRequest request) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();
        validateManagedRoles(request.roles());

        AppUser user = requireEntityUser(userId, entityId);
        ensureNotLastCompanyAdmin(user, request.roles(), user.isEnabled());
        Set<AppUserRole> previousRoles = new java.util.LinkedHashSet<>(user.getRoles());
        user.setRoles(request.roles());
        user.markRolesChanged(actorId);
        AppUser saved = appUserRepository.save(user);

        eventPublisher.publishEvent(new CompanyUserRolesUpdatedEvent(entityId, actorId, actorRole(authentication),
            details(saved, previousRoles)));
        return toResponse(saved);
    }

    public CompanyUserResponse setUserEnabled(Authentication authentication, UUID userId, boolean enabled) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();

        AppUser user = requireEntityUser(userId, entityId);
        ensureNotLastCompanyAdmin(user, user.getRoles(), enabled);
        if (enabled && !user.isEnabled()) {
            user.markRolesChanged(actorId);
        }
        user.setEnabled(enabled);
        AppUser saved = appUserRepository.save(user);
        if (!enabled) {
            // A withdrawn invite / outstanding reset link must not bring the account back (6-02).
            actionTokenRepository.invalidateAllForUser(saved.getId());
        }

        eventPublisher.publishEvent(new CompanyUserDisabledEvent(entityId, actorId, actorRole(authentication),
            details(saved, saved.getRoles())));
        return toResponse(saved);
    }

    public void sendPasswordReset(Authentication authentication, UUID userId) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();

        AppUser user = requireEntityUser(userId, entityId);
        LegalEntity entity = getEntity(entityId);
        String resetToken = createActionToken(user, AppUserActionTokenType.PASSWORD_RESET, actorId);
        eventPublisher.publishEvent(new CompanyUserPasswordResetRequestedEvent(entityId, user.getId(), actorId, null,
            user.getEmail(), secureLinks.seal(customerFrontendUrl + "/reset-password/" + resetToken, user.getId())));

        // CompanyUserPasswordResetRequestedEvent already published above with notification data
    }

    public void deleteUser(Authentication authentication, UUID userId) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();

        AppUser user = requireEntityUser(userId, entityId);
        ensureNotLastCompanyAdmin(user, Set.of(), false);
        appUserRepository.delete(user);

        eventPublisher.publishEvent(new CompanyUserDeletedEvent(entityId, actorId, actorRole(authentication),
            details(user, user.getRoles())));
    }

    @Transactional(readOnly = true)
    public CompanyIdpSettingsResponse getIdpSettings(Authentication authentication) {
        LegalEntity entity = getCurrentEntity(authentication);
        return new CompanyIdpSettingsResponse(
            entity.getIdpIssuerUrl() == null ? "" : entity.getIdpIssuerUrl(),
            entity.getIdpClientId() == null ? "" : entity.getIdpClientId(),
            entity.getIdentityModel(),
            entity.isIdpMfaTrusted(),
            authProperties.isEntraEnabled()
        );
    }

    /**
     * A company administrator may record which tenant they federate from. They may not decide
     * the identity model or whether their tenant's MFA is trusted here — both are
     * operator-controlled, because a customer vouching for their own MFA would let them lower
     * the authentication bar applied to their own users.
     */
    public CompanyIdpSettingsResponse saveIdpSettings(Authentication authentication, CompanyIdpSettingsRequest request) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        LegalEntity entity = getEntity(entityId);
        UUID tenantId = tenantIdFromIssuerUrl(request.issuerUrl());
        if (request.issuerUrl() != null && !request.issuerUrl().isBlank() && tenantId == null) {
            throw new IllegalArgumentException("issuerUrl must contain a valid tenant UUID");
        }
        entity.setIdpIssuerUrl(blankToNull(request.issuerUrl()));
        entity.setIdpClientId(blankToNull(request.clientId()));
        entity.setIdpTenantId(tenantId);
        legalEntityRepository.save(entity);

        eventPublisher.publishEvent(new CompanyIdpSettingsUpdatedEvent(entity.getId(), actorId, null, null));
        return getIdpSettings(authentication);
    }

    @Transactional(readOnly = true)
    public PublicUserActionTokenInfoResponse getRegistrationTokenInfo(String token) {
        AppUser user = findValidActionToken(token, AppUserActionTokenType.REGISTRATION);
        return new PublicUserActionTokenInfoResponse(user.getEmail(), displayName(user));
    }

    public void completeRegistration(PublicUserRegistrationCompleteRequest request) {
        AppUserActionToken token = requireValidActionTokenEntity(request.token(), AppUserActionTokenType.REGISTRATION);
        AppUser user = requireRedeemableUser(token);
        user.setFullName(request.name().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // Deliberately no setEnabled(true): completing a token must never re-enable an account an
        // administrator disabled after the invite (6-02). Invites already create enabled accounts.
        token.setConsumedAt(Instant.now());
        appUserRepository.save(user);
        actionTokenRepository.save(token);
    }

    @Transactional(readOnly = true)
    public PublicUserActionTokenInfoResponse getPasswordResetTokenInfo(String token) {
        AppUser user = findValidActionToken(token, AppUserActionTokenType.PASSWORD_RESET);
        return new PublicUserActionTokenInfoResponse(user.getEmail(), displayName(user));
    }

    public void completePasswordReset(PublicPasswordResetCompleteRequest request) {
        AppUserActionToken token = requireValidActionTokenEntity(request.token(), AppUserActionTokenType.PASSWORD_RESET);
        AppUser user = requireRedeemableUser(token);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setMustChangePassword(false);
        token.setConsumedAt(Instant.now());
        appUserRepository.save(user);
        actionTokenRepository.save(token);
    }

    public AppUser createInitialCompanyAdmin(
            UUID legalEntityId,
            String email,
            String name,
            String password,
            boolean entraEnabled) {
        if (appUserRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new IllegalArgumentException("A user with this email already exists");
        }

        AppUser user = new AppUser();
        user.setEmail(email.trim());
        user.setFullName(name.trim());
        user.setLegalEntityId(legalEntityId);
        user.setRoles(Set.of(AppUserRole.COMPANY_ADMIN));
        user.setEnabled(true);
        user.setAuthProvider(entraEnabled ? UserAuthProvider.ENTRA : UserAuthProvider.LOCAL);
        if (!entraEnabled) {
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("password is required");
            }
            if (password.length() < 8 || password.length() > 200) {
                throw new IllegalArgumentException("password must be between 8 and 200 characters");
            }
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        AppUser saved = appUserRepository.save(user);
        eventPublisher.publishEvent(new CompanyAdminBootstrappedEvent(legalEntityId, null, null, null));
        return saved;
    }

    private AppUser findValidActionToken(String cleartextToken, AppUserActionTokenType tokenType) {
        AppUserActionToken token = requireValidActionTokenEntity(cleartextToken, tokenType);
        return requireRedeemableUser(token);
    }

    /**
     * The account behind a valid registration / reset token, only while it may still be redeemed:
     * enabled, and (for customer users) with an ACTIVE entity. The same generic error as an invalid
     * token, so the public endpoint does not reveal why (6-02).
     */
    private AppUser requireRedeemableUser(AppUserActionToken token) {
        AppUser user = requireUser(token.getAppUserId());
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        if (user.getLegalEntityId() != null) {
            boolean active = legalEntityRepository.findById(user.getLegalEntityId())
                .map(e -> e.getStatus() == EntityStatus.ACTIVE).orElse(false);
            if (!active) {
                throw new IllegalArgumentException("Invalid or expired token");
            }
        }
        return user;
    }

    private AppUserActionToken requireValidActionTokenEntity(String cleartextToken, AppUserActionTokenType tokenType) {
        String hash = sha256Hex(cleartextToken);
        AppUserActionToken token = actionTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new IllegalArgumentException("Invalid or expired token"));
        if (token.getTokenType() != tokenType || !token.isValid()) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        return token;
    }

    private String createActionToken(AppUser user, AppUserActionTokenType tokenType, UUID actorId) {
        invalidateActiveTokens(user.getId(), tokenType);
        byte[] randomBytes = new byte[TOKEN_BYTES];
        new java.security.SecureRandom().nextBytes(randomBytes);
        String cleartext = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        AppUserActionToken token = new AppUserActionToken();
        token.setAppUserId(user.getId());
        token.setTokenHash(sha256Hex(cleartext));
        token.setTokenType(tokenType);
        token.setExpiresAt(Instant.now().plus(userActionTokenTtlHours, ChronoUnit.HOURS));
        token.setCreatedBy(actorId);
        actionTokenRepository.save(token);
        return cleartext;
    }

    private void invalidateActiveTokens(UUID userId, AppUserActionTokenType tokenType) {
        actionTokenRepository.findByAppUserIdAndTokenTypeAndConsumedAtIsNull(userId, tokenType)
            .forEach(existing -> {
                existing.setConsumedAt(Instant.now());
                actionTokenRepository.save(existing);
            });
    }

    private void ensureNotLastCompanyAdmin(AppUser user, Set<AppUserRole> nextRoles, boolean nextEnabled) {
        if (!user.hasRole(AppUserRole.COMPANY_ADMIN)) {
            return;
        }
        if (nextEnabled && nextRoles.contains(AppUserRole.COMPANY_ADMIN)) {
            return;
        }
        long remainingAdmins = appUserRepository.countEnabledUsersByLegalEntityIdAndRole(
            user.getLegalEntityId(),
            AppUserRole.COMPANY_ADMIN,
            user.getId()
        );
        if (remainingAdmins == 0) {
            throw new InvalidStateTransitionException("A company must keep at least one enabled COMPANY_ADMIN");
        }
    }

    private void validateManagedRoles(Set<AppUserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        for (AppUserRole role : roles) {
            if (!MANAGED_ROLES.contains(role)) {
                throw new IllegalArgumentException("Role " + role + " cannot be assigned in company user management");
            }
        }
    }

    /** Audit payload of company user-lifecycle events: who, which roles before/after, enabled. */
    private static Map<String, Object> details(AppUser user, Set<AppUserRole> previousRoles) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("userId", user.getId().toString());
        d.put("email", user.getEmail());
        d.put("roles", List.copyOf(names(user.getRoles())));
        d.put("previousRoles", List.copyOf(names(previousRoles)));
        d.put("legalEntityId", user.getLegalEntityId() == null ? "" : user.getLegalEntityId().toString());
        d.put("enabled", user.isEnabled());
        return d;
    }

    private static Set<String> names(Set<AppUserRole> roles) {
        Set<String> out = new TreeSet<>();
        for (AppUserRole r : roles) out.add(r.name());
        return out;
    }

    private static String actorRole(Authentication authentication) {
        return SecurityUtils.primaryRole(authentication, "COMPANY_ADMIN");
    }

    private void ensureLocalLifecycleEnabled() {
        if (authProperties.isEntraEnabled()) {
            throw new UnsupportedOperationException(
                "User lifecycle changes are managed by the configured identity provider in Entra mode"
            );
        }
    }

    private UUID requireEntityId(Authentication authentication) {
        UUID entityId = SecurityUtils.extractEntityId(authentication);
        if (entityId == null) {
            throw new IllegalArgumentException("Authenticated entity context is missing");
        }
        return entityId;
    }

    private LegalEntity getCurrentEntity(Authentication authentication) {
        return getEntity(requireEntityId(authentication));
    }

    private LegalEntity getEntity(UUID entityId) {
        return legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
    }

    private AppUser requireEntityUser(UUID userId, UUID entityId) {
        return appUserRepository.findByIdAndLegalEntityId(userId, entityId)
            .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
    }

    private AppUser requireUser(UUID userId) {
        return appUserRepository.findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
    }

    private CompanyUserResponse toResponse(AppUser user) {
        return new CompanyUserResponse(
            user.getId(),
            user.getEmail(),
            displayName(user),
            user.getRoles(),
            user.getLegalEntityId(),
            user.isEnabled(),
            user.getLastLoginAt(),
            user.getAuthProvider(),
            user.getAuthProvider() == UserAuthProvider.LOCAL && (user.getPasswordHash() == null || user.getPasswordHash().isBlank())
        );
    }

    private static String displayName(AppUser user) {
        return user.getFullName() == null || user.getFullName().isBlank() ? user.getEmail() : user.getFullName();
    }

    /**
     * Extracts the tenant id from an Entra issuer URL such as
     * {@code https://login.microsoftonline.com/<tenant-id>/v2.0}, so federated users can later be
     * recognised by the {@code tid} on their token. Returns null when no tenant UUID can be
     * extracted; callers reject such settings instead of storing an issuer that can never match.
     */
    private static UUID tenantIdFromIssuerUrl(String issuerUrl) {
        if (issuerUrl == null || issuerUrl.isBlank()) {
            return null;
        }
        for (String segment : issuerUrl.split("/")) {
            try {
                return UUID.fromString(segment);
            } catch (IllegalArgumentException ignored) {
                // Not this segment; keep looking.
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
