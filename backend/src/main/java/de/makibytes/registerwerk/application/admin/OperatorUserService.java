package de.makibytes.registerwerk.application.admin;

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
import de.makibytes.registerwerk.domain.enums.UserAuthProvider;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AppUserActionTokenRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AppUserRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import de.makibytes.registerwerk.web.dto.OperatorInviteRequest;
import de.makibytes.registerwerk.web.dto.OperatorUserResponse;
import de.makibytes.registerwerk.web.dto.UpdateCompanyUserRolesRequest;
import de.makibytes.registerwerk.web.security.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class OperatorUserService {

    private static final int TOKEN_BYTES = 36;
    private static final Set<AppUserRole> FORBIDDEN_FOR_COMPANY_USERS = Set.of(
        AppUserRole.REGISTRY_ADMIN, AppUserRole.AUDIT, AppUserRole.COMPLIANCE_OFFICER
    );

    private final AppUserRepository appUserRepository;
    private final AppUserActionTokenRepository actionTokenRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final PasswordEncoder passwordEncoder;
    private final CompanyUserInvitationEmailService invitationEmailService;
    private final PasswordResetEmailService passwordResetEmailService;
    private final AuditEventPublisher auditEventPublisher;
    private final RegisterwerkAuthProperties authProperties;
    private final String customerFrontendUrl;
    private final long userActionTokenTtlHours;

    public OperatorUserService(
            AppUserRepository appUserRepository,
            AppUserActionTokenRepository actionTokenRepository,
            LegalEntityRepository legalEntityRepository,
            PasswordEncoder passwordEncoder,
            CompanyUserInvitationEmailService invitationEmailService,
            PasswordResetEmailService passwordResetEmailService,
            AuditEventPublisher auditEventPublisher,
            RegisterwerkAuthProperties authProperties,
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
        this.customerFrontendUrl = customerFrontendUrl;
        this.userActionTokenTtlHours = userActionTokenTtlHours;
    }

    @Transactional(readOnly = true)
    public Page<OperatorUserResponse> list(
            UUID legalEntityId, AppUserRole role, Boolean enabled,
            Boolean operatorOnly, String search, int page, int size) {
        Specification<AppUser> spec = buildSpec(legalEntityId, role, enabled, operatorOnly, search);
        PageRequest pageRequest = PageRequest.of(page, size,
            Sort.by("fullName").ascending().and(Sort.by("email").ascending()));
        return appUserRepository.findAll(spec, pageRequest).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public OperatorUserResponse get(UUID userId) {
        return toResponse(requireUser(userId));
    }

    public OperatorUserResponse invite(Authentication authentication, OperatorInviteRequest request) {
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();
        validateRolesForContext(request.legalEntityId(), request.roles());

        if (appUserRepository.findByEmailIgnoreCase(request.email()).isPresent()) {
            throw new IllegalArgumentException("A user with this email already exists");
        }

        String entityName = "Registerwerk Operator Portal";
        if (request.legalEntityId() != null) {
            entityName = getEntity(request.legalEntityId()).getCurrentName();
        }

        AppUser user = new AppUser();
        user.setEmail(request.email().trim());
        user.setFullName(request.name().trim());
        user.setLegalEntityId(request.legalEntityId());
        user.setAuthProvider(UserAuthProvider.LOCAL);
        user.setEnabled(true);
        user.setCreatedBy(actorId);
        user.setRoles(request.roles());
        AppUser saved = appUserRepository.save(user);

        String registrationToken = createActionToken(saved, AppUserActionTokenType.REGISTRATION, actorId);
        invitationEmailService.sendInvite(
            saved.getEmail(),
            saved.getFullName(),
            entityName,
            customerFrontendUrl + "/register/" + registrationToken
        );

        auditEventPublisher.publish(
            "OPERATOR_USER_INVITED",
            "AppUser",
            saved.getId(),
            actorId,
            null,
            Map.of("email", saved.getEmail(), "roles", roleNames(saved.getRoles()))
        );
        return toResponse(saved);
    }

    public OperatorUserResponse updateRoles(Authentication authentication, UUID userId, UpdateCompanyUserRolesRequest request) {
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();
        ensureNotSelf(actorId, userId, "Cannot modify your own roles via admin user management");

        AppUser user = requireUser(userId);
        validateRolesForContext(user.getLegalEntityId(), request.roles());
        ensureNotLastRegistryAdmin(user, request.roles(), user.isEnabled());

        user.setRoles(request.roles());
        AppUser saved = appUserRepository.save(user);

        auditEventPublisher.publish(
            "OPERATOR_USER_ROLES_UPDATED",
            "AppUser",
            saved.getId(),
            actorId,
            null,
            Map.of("email", saved.getEmail(), "roles", roleNames(saved.getRoles()))
        );
        return toResponse(saved);
    }

    public OperatorUserResponse setEnabled(Authentication authentication, UUID userId, boolean newEnabled) {
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();
        if (!newEnabled) {
            ensureNotSelf(actorId, userId, "Cannot disable your own account");
        }

        AppUser user = requireUser(userId);
        ensureNotLastRegistryAdmin(user, user.getRoles(), newEnabled);
        user.setEnabled(newEnabled);
        AppUser saved = appUserRepository.save(user);

        auditEventPublisher.publish(
            newEnabled ? "OPERATOR_USER_ENABLED" : "OPERATOR_USER_DISABLED",
            "AppUser",
            saved.getId(),
            actorId,
            null,
            Map.of("email", saved.getEmail())
        );
        return toResponse(saved);
    }

    public void sendPasswordReset(Authentication authentication, UUID userId) {
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();

        AppUser user = requireUser(userId);
        String entityName = resolveEntityName(user.getLegalEntityId());
        String resetToken = createActionToken(user, AppUserActionTokenType.PASSWORD_RESET, actorId);
        passwordResetEmailService.sendReset(
            user.getEmail(),
            displayName(user),
            entityName,
            customerFrontendUrl + "/reset-password/" + resetToken
        );

        auditEventPublisher.publish(
            "OPERATOR_USER_PASSWORD_RESET_SENT",
            "AppUser",
            user.getId(),
            actorId,
            null,
            Map.of("email", user.getEmail())
        );
    }

    public void delete(Authentication authentication, UUID userId) {
        UUID actorId = SecurityUtils.extractUserId(authentication);
        ensureLocalLifecycleEnabled();
        ensureNotSelf(actorId, userId, "Cannot delete your own account");

        AppUser user = requireUser(userId);
        ensureNotLastRegistryAdmin(user, Set.of(), false);
        appUserRepository.delete(user);

        auditEventPublisher.publish(
            "OPERATOR_USER_DELETED",
            "AppUser",
            userId,
            actorId,
            null,
            Map.of("email", user.getEmail())
        );
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private Specification<AppUser> buildSpec(
            UUID legalEntityId, AppUserRole role, Boolean enabled,
            Boolean operatorOnly, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (legalEntityId != null) {
                predicates.add(cb.equal(root.get("legalEntityId"), legalEntityId));
            }
            if (Boolean.TRUE.equals(operatorOnly)) {
                predicates.add(cb.isNull(root.get("legalEntityId")));
            } else if (Boolean.FALSE.equals(operatorOnly)) {
                predicates.add(cb.isNotNull(root.get("legalEntityId")));
            }
            if (role != null) {
                predicates.add(cb.isMember(role, root.get("roles")));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("email")), pattern),
                    cb.like(cb.lower(cb.coalesce(root.get("fullName"), "")), pattern)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private void validateRolesForContext(UUID legalEntityId, Set<AppUserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        if (legalEntityId != null) {
            // Company users cannot hold operator-only roles
            for (AppUserRole role : roles) {
                if (FORBIDDEN_FOR_COMPANY_USERS.contains(role)) {
                    throw new IllegalArgumentException(
                        "Role " + role + " cannot be assigned to company-scoped users"
                    );
                }
            }
        }
    }

    private void ensureNotLastRegistryAdmin(AppUser user, Set<AppUserRole> nextRoles, boolean nextEnabled) {
        if (!user.hasRole(AppUserRole.REGISTRY_ADMIN)) return;
        if (nextEnabled && nextRoles.contains(AppUserRole.REGISTRY_ADMIN)) return;
        long remaining = appUserRepository.countEnabledUsersWithRole(AppUserRole.REGISTRY_ADMIN, user.getId());
        if (remaining == 0) {
            throw new InvalidStateTransitionException(
                "The system must keep at least one enabled REGISTRY_ADMIN"
            );
        }
    }

    private void ensureNotSelf(UUID actorId, UUID targetId, String message) {
        if (actorId != null && actorId.equals(targetId)) {
            throw new IllegalArgumentException(message);
        }
    }

    private void ensureLocalLifecycleEnabled() {
        if (authProperties.isEntraEnabled()) {
            throw new UnsupportedOperationException(
                "User lifecycle changes are managed by the configured identity provider in Entra mode"
            );
        }
    }

    private AppUser requireUser(UUID userId) {
        return appUserRepository.findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("AppUser", userId));
    }

    private LegalEntity getEntity(UUID entityId) {
        return legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));
    }

    private String resolveEntityName(UUID legalEntityId) {
        if (legalEntityId == null) return "Registerwerk Operator Portal";
        return legalEntityRepository.findById(legalEntityId)
            .map(LegalEntity::getCurrentName)
            .orElse("Unknown entity");
    }

    private OperatorUserResponse toResponse(AppUser user) {
        String entityName = resolveEntityName(user.getLegalEntityId());
        return new OperatorUserResponse(
            user.getId(),
            user.getEmail(),
            displayName(user),
            user.getRoles(),
            user.getLegalEntityId(),
            entityName,
            user.isEnabled(),
            user.getLastLoginAt(),
            user.getAuthProvider(),
            user.getAuthProvider() == UserAuthProvider.LOCAL
                && (user.getPasswordHash() == null || user.getPasswordHash().isBlank())
        );
    }

    private static String displayName(AppUser user) {
        return user.getFullName() == null || user.getFullName().isBlank() ? user.getEmail() : user.getFullName();
    }

    private static List<String> roleNames(Set<AppUserRole> roles) {
        return roles.stream().map(Enum::name).toList();
    }

    private String createActionToken(AppUser user, AppUserActionTokenType tokenType, UUID actorId) {
        actionTokenRepository.findByAppUserIdAndTokenTypeAndConsumedAtIsNull(user.getId(), tokenType)
            .forEach(existing -> {
                existing.setConsumedAt(Instant.now());
                actionTokenRepository.save(existing);
            });

        byte[] randomBytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(randomBytes);
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

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
