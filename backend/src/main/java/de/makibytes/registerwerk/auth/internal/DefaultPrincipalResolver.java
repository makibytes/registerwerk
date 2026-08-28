package de.makibytes.registerwerk.auth.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.PrincipalResolver;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.events.OidcUserProvisionedEvent;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an authenticated principal to its {@code app_user} row.
 *
 * <p>Lookup order for an Entra token, most to least stable:
 * <ol>
 *   <li>{@code oid} → {@code entra_object_id}. The only identifier Entra guarantees is stable.</li>
 *   <li>email → backfill {@code entra_object_id}. Bridges accounts invited before this column
 *       existed, and accounts an operator pre-created for a user who has not yet signed in.</li>
 *   <li>JIT-provision a new row.</li>
 * </ol>
 *
 * <p>Roles always come from the {@code app_user} row, never from the token's {@code roles} claim
 * — including at first sight. A JIT-provisioned account is created disabled with zero roles and
 * publishes {@link de.makibytes.registerwerk.auth.events.OidcUserProvisionedEvent}; an operator
 * must review and enable it, assigning least-privilege roles through {@code OperatorUserService}
 * / {@code CompanyUserService}. That keeps a single authority for authorisation and means neither
 * a compromised nor a misconfigured IdP app-role assignment can hand out access on its own.
 */
@Component
class DefaultPrincipalResolver implements PrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultPrincipalResolver.class);

    private final AppUserRepository appUserRepository;
    private final ApplicationEventPublisher eventPublisher;

    DefaultPrincipalResolver(AppUserRepository appUserRepository, ApplicationEventPublisher eventPublisher) {
        this.appUserRepository = appUserRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public Optional<AppUser> resolve(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        if (isLocallyMinted(jwt)) {
            return Optional.ofNullable(SecurityUtils.extractUserId(authentication))
                    .flatMap(appUserRepository::findById);
        }
        // `oid` is an Entra-specific claim (the Microsoft identity platform's stable per-tenant
        // user id). Its absence means the token came from some other JWKS-validated OIDC issuer
        // (JWT_ISSUER_URI pointed at Okta/Keycloak/ForgeRock/Auth0/…), not that it's malformed.
        return jwt.getClaimAsString("oid") != null
                ? resolveEntraPrincipal(jwt)
                : resolveGenericOidcPrincipal(jwt);
    }

    @Override
    public AppUser requireUser(Authentication authentication) {
        return resolve(authentication).orElseThrow(() -> new AccessDeniedException(
                "No Registerwerk account could be resolved for the authenticated principal."));
    }

    @Override
    public UUID entraObjectIdOf(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        return parseUuid(jwt.getClaimAsString("oid"));
    }

    private Optional<AppUser> resolveEntraPrincipal(Jwt jwt) {
        UUID objectId = parseUuid(jwt.getClaimAsString("oid"));
        String email = firstNonBlank(
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("upn"));

        if (objectId == null && email == null) {
            log.warn("Entra token carries neither an oid nor an email claim — cannot resolve an account");
            return Optional.empty();
        }

        Optional<AppUser> byObjectId = objectId == null
                ? Optional.empty()
                : appUserRepository.findByEntraObjectId(objectId);
        if (byObjectId.isPresent()) {
            return byObjectId.map(user -> touch(user, jwt, objectId));
        }

        if (email != null) {
            Optional<AppUser> byEmail = appUserRepository.findByEmailIgnoreCase(email);
            if (byEmail.isPresent()) {
                AppUser user = byEmail.get();
                if (objectId != null && user.getEntraObjectId() == null) {
                    log.info("Binding Entra oid to existing account: email={} oid={}", email, objectId);
                }
                return Optional.of(touch(user, jwt, objectId));
            }
        }

        return Optional.of(provision(jwt, objectId, email));
    }

    /** Keeps the mirrored identity columns current without touching roles or enabled state. */
    private AppUser touch(AppUser user, Jwt jwt, UUID objectId) {
        boolean dirty = false;
        if (objectId != null && !objectId.equals(user.getEntraObjectId())) {
            user.setEntraObjectId(objectId);
            dirty = true;
        }
        UUID tenantId = parseUuid(jwt.getClaimAsString("tid"));
        if (tenantId != null && !tenantId.equals(user.getEntraTenantId())) {
            user.setEntraTenantId(tenantId);
            dirty = true;
        }
        if (user.getAuthProvider() != UserAuthProvider.ENTRA) {
            user.setAuthProvider(UserAuthProvider.ENTRA);
            dirty = true;
        }
        return dirty ? appUserRepository.save(user) : user;
    }

    /**
     * Creates an account for a principal Entra has authenticated but we have never seen.
     *
     * <p>The new account is created disabled with no functional roles. Operator approval enables
     * the account and assigns least-privilege roles in the {@code app_user} row.
     */
    private AppUser provision(Jwt jwt, UUID objectId, String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setFullName(firstNonBlank(jwt.getClaimAsString("name"), email));
        user.setEntraObjectId(objectId);
        user.setEntraTenantId(parseUuid(jwt.getClaimAsString("tid")));
        user.setAuthProvider(UserAuthProvider.ENTRA);
        user.setEnabled(false);
        user.setLastLoginAt(Instant.now());
        user.setLegalEntityId(parseUuid(claim(jwt, "entity_id", "entityId")));

        user.setRoles(EnumSet.noneOf(AppUserRole.class));

        AppUser saved = appUserRepository.save(user);
        eventPublisher.publishEvent(new OidcUserProvisionedEvent(
                saved.getId(), saved.getEmail(), saved.getAuthProvider().name()));
        log.info("Provisioned disabled account for Entra principal: id={} oid={} email={}",
                saved.getId(), objectId, email);
        return saved;
    }

    /**
     * Resolves a principal from a non-Entra OIDC issuer — any JWKS-validated token without an
     * Entra {@code oid} claim. Keyed on {@code sub}, the one identifier every OIDC provider
     * guarantees stable within its issuer, rather than the Entra-specific {@code oid}/{@code tid}
     * this class otherwise relies on. See {@link de.makibytes.registerwerk.auth.api.UserAuthProvider#OIDC}.
     *
     * <p>Unlike the Entra path, provisioning a new account here requires an email claim —
     * {@code app_user.email} is {@code NOT NULL}, and a subject-only OIDC access token (no
     * {@code email}/{@code preferred_username}/{@code upn} scope) has nothing else to seed it
     * with. Such a token can still authenticate once an operator has pre-provisioned the account
     * (email match) or the subject has signed in before (externalSubject match); it just cannot
     * self-provision on first sight, unlike Entra where Conditional Access already gated entry.
     */
    private Optional<AppUser> resolveGenericOidcPrincipal(Jwt jwt) {
        String subject = jwt.getSubject();
        String email = firstNonBlank(
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("upn"));

        if (subject == null && email == null) {
            log.warn("OIDC token carries neither a sub nor an email claim — cannot resolve an account");
            return Optional.empty();
        }

        Optional<AppUser> bySubject = subject == null
                ? Optional.empty()
                : appUserRepository.findByExternalSubject(subject);
        if (bySubject.isPresent()) {
            return bySubject.map(user -> touchOidc(user, subject));
        }

        if (email != null) {
            Optional<AppUser> byEmail = appUserRepository.findByEmailIgnoreCase(email);
            if (byEmail.isPresent()) {
                return Optional.of(touchOidc(byEmail.get(), subject));
            }
        }

        if (email == null) {
            log.warn("OIDC token for sub={} carries no email claim and no existing account matches — "
                    + "cannot provision (app_user.email is required)", subject);
            return Optional.empty();
        }

        return Optional.of(provisionOidc(jwt, subject, email));
    }

    /** Keeps the mirrored identity column current without touching roles or enabled state. */
    private AppUser touchOidc(AppUser user, String subject) {
        boolean dirty = false;
        if (subject != null && !subject.equals(user.getExternalSubject())) {
            user.setExternalSubject(subject);
            dirty = true;
        }
        if (user.getAuthProvider() != UserAuthProvider.OIDC) {
            user.setAuthProvider(UserAuthProvider.OIDC);
            dirty = true;
        }
        return dirty ? appUserRepository.save(user) : user;
    }

    /** Creates an account for a non-Entra OIDC principal seen for the first time. */
    private AppUser provisionOidc(Jwt jwt, String subject, String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setFullName(firstNonBlank(jwt.getClaimAsString("name"), email));
        user.setExternalSubject(subject);
        user.setAuthProvider(UserAuthProvider.OIDC);
        user.setEnabled(false);
        user.setLastLoginAt(Instant.now());
        user.setLegalEntityId(parseUuid(claim(jwt, "entity_id", "entityId")));

        user.setRoles(EnumSet.noneOf(AppUserRole.class));

        AppUser saved = appUserRepository.save(user);
        eventPublisher.publishEvent(new OidcUserProvisionedEvent(
                saved.getId(), saved.getEmail(), saved.getAuthProvider().name()));
        log.info("Provisioned disabled account for generic OIDC principal: id={} sub={} email={}",
                saved.getId(), subject, email);
        return saved;
    }

    private static boolean isLocallyMinted(Jwt jwt) {
        return JwtMintingService.LOCAL_ISSUER.equals(jwt.getClaimAsString("iss"));
    }

    private static String claim(Jwt jwt, String... names) {
        for (String name : names) {
            String value = jwt.getClaimAsString(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
