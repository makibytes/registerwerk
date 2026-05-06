package de.makibytes.registerwerk.application.customer;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.application.exception.InvalidStateTransitionException;
import de.makibytes.registerwerk.application.notification.CompanyUserInvitationEmailService;
import de.makibytes.registerwerk.application.notification.PasswordResetEmailService;
import de.makibytes.registerwerk.config.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.domain.entity.AppUser;
import de.makibytes.registerwerk.domain.entity.AppUserActionToken;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.enums.AppUserActionTokenType;
import de.makibytes.registerwerk.domain.enums.AppUserRole;
import de.makibytes.registerwerk.domain.enums.EntityStatus;
import de.makibytes.registerwerk.domain.enums.ExternalReferenceSubjectType;
import de.makibytes.registerwerk.domain.enums.UserAuthProvider;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AppUserActionTokenRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AppUserRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import de.makibytes.registerwerk.web.dto.CompanyIdpSettingsRequest;
import de.makibytes.registerwerk.web.dto.CompanyIdpSettingsResponse;
import de.makibytes.registerwerk.web.dto.CompanyMeResponse;
import de.makibytes.registerwerk.web.dto.CompanyUserResponse;
import de.makibytes.registerwerk.web.dto.InviteCompanyUserRequest;
import de.makibytes.registerwerk.web.dto.PublicPasswordResetCompleteRequest;
import de.makibytes.registerwerk.web.dto.PublicUserActionTokenInfoResponse;
import de.makibytes.registerwerk.web.dto.PublicUserRegistrationCompleteRequest;
import de.makibytes.registerwerk.web.dto.UpdateCompanyUserRolesRequest;
import de.makibytes.registerwerk.web.security.SecurityUtils;
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
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class CompanyUserService {

    private static final Logger log = LoggerFactory.getLogger(CompanyUserService.class);
    private static final int TOKEN_BYTES = 36;

    private final AppUserRepository appUserRepository;
    private final AppUserActionTokenRepository actionTokenRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final PasswordEncoder passwordEncoder;
    private final CompanyUserInvitationEmailService invitationEmailService;
    private final PasswordResetEmailService passwordResetEmailService;
    private final AuditEventPublisher auditEventPublisher;
    private final RegisterwerkAuthProperties authProperties;
    private final CompanyExternalReferenceService companyExternalReferenceService;
    private final String customerFrontendUrl;
    private final long userActionTokenTtlHours;

    public CompanyUserService(
            AppUserRepository appUserRepository,
            AppUserActionTokenRepository actionTokenRepository,
            LegalEntityRepository legalEntityRepository,
            PasswordEncoder passwordEncoder,
            CompanyUserInvitationEmailService invitationEmailService,
            PasswordResetEmailService passwordResetEmailService,
            AuditEventPublisher auditEventPublisher,
            RegisterwerkAuthProperties authProperties,
            CompanyExternalReferenceService companyExternalReferenceService,
            @Value("${registerwerk.onboarding.frontend-url:http://localhost:4201}") String customerFrontendUrl,
            @Value("${registerwerk.onboarding.user-action-ttl-hours:48}") long userActionTokenTtlHours) {
        this.appUserRepository = appUserRepository;
        this.actionTokenRepository = actionTokenRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.passwordEncoder = passwordEncoder;
        this.invitationEmailService = invitationEmailService;
        this.passwordResetEmailService = passwordResetEmailService;
        this.auditEventPublisher = auditEventPublisher;
        this.authProperties = authProperties;
        this.companyExternalReferenceService = companyExternalReferenceService;
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
            companyExternalReferenceService
                    .findExternalId(authentication, ExternalReferenceSubjectType.LEGAL_ENTITY, entity.getId())
                    .orElse(null)
        );
    }

    @Transactional(readOnly = true)
    public List<CompanyUserResponse> listUsers(Authentication authentication) {
        UUID entityId = requireEntityId(authentication);
        return appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId).stream()
            .map(this::toResponse)
            .toList();
    }

    public void syncAuthenticatedPrincipal(Authentication authentication) {
        String email = SecurityUtils.extractEmail(authentication);
        UUID entityId = SecurityUtils.extractEntityId(authentication);
        if (email == null || entityId == null) {
            return;
        }

        AppUser user = appUserRepository.findByEmailIgnoreCase(email)
            .orElseGet(AppUser::new);

        user.setEmail(email);
        user.setLegalEntityId(entityId);
        user.setEnabled(true);
        user.setLastLoginAt(Instant.now());
        user.setFullName(SecurityUtils.extractDisplayName(authentication));
        if (authProperties.isEntraEnabled()) {
            user.setAuthProvider(UserAuthProvider.ENTRA);
            Set<AppUserRole> mappedRoles = mapRoles(SecurityUtils.extractRoles(authentication));
            if (!mappedRoles.isEmpty()) {
                user.setRoles(mappedRoles);
            }
        }
        appUserRepository.save(user);
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
        AppUser saved = appUserRepository.save(user);

        String registrationToken = createActionToken(saved, AppUserActionTokenType.REGISTRATION, actorId);
        invitationEmailService.sendInvite(
            saved.getEmail(),
            saved.getFullName(),
            entity.getCurrentName(),
            customerFrontendUrl + "/register/" + registrationToken
        );

        auditEventPublisher.publish(
            "COMPANY_USER_INVITED",
            "AppUser",
            saved.getId(),
            actorId,
            null,
            Map.of("entityId", entityId.toString(), "email", saved.getEmail(), "roles", roleNames(saved.getRoles()))
        );
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
        user.setRoles(request.roles());
        AppUser saved = appUserRepository.save(user);

        auditEventPublisher.publish(
            "COMPANY_USER_ROLES_UPDATED",
            "AppUser",
            saved.getId(),
            actorId,
            null,
            Map.of("entityId", entityId.toString(), "roles", roleNames(saved.getRoles()))
        );
        return toResponse(saved);
    }

    public CompanyUserResponse setUserEnabled(Authentication authentication, UUID userId, boolean enabled) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();

        AppUser user = requireEntityUser(userId, entityId);
        ensureNotLastCompanyAdmin(user, user.getRoles(), enabled);
        user.setEnabled(enabled);
        AppUser saved = appUserRepository.save(user);

        auditEventPublisher.publish(
            enabled ? "COMPANY_USER_ENABLED" : "COMPANY_USER_DISABLED",
            "AppUser",
            saved.getId(),
            actorId,
            null,
            Map.of("entityId", entityId.toString(), "email", saved.getEmail())
        );
        return toResponse(saved);
    }

    public void sendPasswordReset(Authentication authentication, UUID userId) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();

        AppUser user = requireEntityUser(userId, entityId);
        LegalEntity entity = getEntity(entityId);
        String resetToken = createActionToken(user, AppUserActionTokenType.PASSWORD_RESET, actorId);
        passwordResetEmailService.sendReset(
            user.getEmail(),
            displayName(user),
            entity.getCurrentName(),
            customerFrontendUrl + "/reset-password/" + resetToken
        );

        auditEventPublisher.publish(
            "COMPANY_USER_PASSWORD_RESET_REQUESTED",
            "AppUser",
            user.getId(),
            actorId,
            null,
            Map.of("entityId", entityId.toString(), "email", user.getEmail())
        );
    }

    public void deleteUser(Authentication authentication, UUID userId) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();

        AppUser user = requireEntityUser(userId, entityId);
        ensureNotLastCompanyAdmin(user, Set.of(), false);
        appUserRepository.delete(user);

        auditEventPublisher.publish(
            "COMPANY_USER_DELETED",
            "AppUser",
            userId,
            actorId,
            null,
            Map.of("entityId", entityId.toString(), "email", user.getEmail())
        );
    }

    @Transactional(readOnly = true)
    public CompanyIdpSettingsResponse getIdpSettings(Authentication authentication) {
        LegalEntity entity = getCurrentEntity(authentication);
        return new CompanyIdpSettingsResponse(
            entity.getIdpIssuerUrl() == null ? "" : entity.getIdpIssuerUrl(),
            entity.getIdpClientId() == null ? "" : entity.getIdpClientId(),
            "",
            entity.getIdpClientSecret() != null && !entity.getIdpClientSecret().isBlank(),
            authProperties.isEntraEnabled()
        );
    }

    public CompanyIdpSettingsResponse saveIdpSettings(Authentication authentication, CompanyIdpSettingsRequest request) {
        UUID entityId = requireEntityId(authentication);
        UUID actorId = SecurityUtils.extractUserId(authentication);
        LegalEntity entity = getEntity(entityId);
        entity.setIdpIssuerUrl(blankToNull(request.issuerUrl()));
        entity.setIdpClientId(blankToNull(request.clientId()));
        if (request.clientSecret() != null && !request.clientSecret().isBlank()) {
            entity.setIdpClientSecret(request.clientSecret().trim());
        }
        legalEntityRepository.save(entity);

        auditEventPublisher.publish(
            "COMPANY_IDP_SETTINGS_UPDATED",
            "LegalEntity",
            entity.getId(),
            actorId,
            null,
            Map.of("issuerUrl", entity.getIdpIssuerUrl(), "clientId", entity.getIdpClientId())
        );
        return getIdpSettings(authentication);
    }

    @Transactional(readOnly = true)
    public PublicUserActionTokenInfoResponse getRegistrationTokenInfo(String token) {
        AppUser user = findValidActionToken(token, AppUserActionTokenType.REGISTRATION);
        return new PublicUserActionTokenInfoResponse(user.getEmail(), displayName(user));
    }

    public void completeRegistration(PublicUserRegistrationCompleteRequest request) {
        AppUserActionToken token = requireValidActionTokenEntity(request.token(), AppUserActionTokenType.REGISTRATION);
        AppUser user = requireUser(token.getAppUserId());
        user.setFullName(request.name().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEnabled(true);
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
        AppUser user = requireUser(token.getAppUserId());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
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
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        AppUser saved = appUserRepository.save(user);
        auditEventPublisher.publish(
            "COMPANY_ADMIN_BOOTSTRAPPED",
            "AppUser",
            saved.getId(),
            null,
            null,
            Map.of("entityId", legalEntityId.toString(), "email", saved.getEmail())
        );
        return saved;
    }

    private AppUser findValidActionToken(String cleartextToken, AppUserActionTokenType tokenType) {
        AppUserActionToken token = requireValidActionTokenEntity(cleartextToken, tokenType);
        return requireUser(token.getAppUserId());
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
        if (roles.contains(AppUserRole.REGISTRY_ADMIN)
                || roles.contains(AppUserRole.AUDIT)
                || roles.contains(AppUserRole.COMPLIANCE_OFFICER)) {
            throw new IllegalArgumentException("Operator roles cannot be assigned in company user management");
        }
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

    private static Set<AppUserRole> mapRoles(List<String> roles) {
        EnumSet<AppUserRole> mapped = EnumSet.noneOf(AppUserRole.class);
        for (String role : roles) {
            try {
                mapped.add(AppUserRole.valueOf(role));
            } catch (IllegalArgumentException ignored) {
                log.debug("Ignoring unmapped role from token: {}", role);
            }
        }
        return mapped;
    }

    private static List<String> roleNames(Set<AppUserRole> roles) {
        return roles.stream().map(Enum::name).toList();
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
