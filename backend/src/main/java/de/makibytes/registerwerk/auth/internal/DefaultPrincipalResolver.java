package de.makibytes.registerwerk.auth.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.EntityActivityPort;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.events.IdentityBoundEvent;
import de.makibytes.registerwerk.auth.events.IdentityRebindRefusedEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final RegisterwerkAuthProperties authProperties;
    private final EntityActivityPort entityPort;
    private final UUID configuredTenantId;
    private final TransactionTemplate newTx;

    /** One refusal event per (account, presented identity) per hour; the filter resolves on every request. */
    private final Cache<String, Boolean> refusalsAnnounced =
            Caffeine.newBuilder().expireAfterWrite(java.time.Duration.ofHours(1)).maximumSize(10_000).build();

    DefaultPrincipalResolver(AppUserRepository appUserRepository, ApplicationEventPublisher eventPublisher,
                             RegisterwerkAuthProperties authProperties, EntityActivityPort entityPort,
                             @Value("${registerwerk.entra.tenant-id:}") String entraTenantId,
                             PlatformTransactionManager txManager) {
        this.appUserRepository = appUserRepository;
        this.eventPublisher = eventPublisher;
        this.authProperties = authProperties;
        this.entityPort = entityPort;
        this.configuredTenantId = parseUuid(entraTenantId == null ? null : entraTenantId.trim());
        // The refusal is followed by an AccessDeniedException that rolls the caller's transaction
        // back; the audit event must survive that.
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    @Transactional
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
                // Never "adopts" a row silently: see linkEntra for the conditions (6-05).
                return linkEntra(byEmail.get(), jwt, objectId, email);
            }
        }

        return Optional.of(provisionDisabled(jwt, email, UserAuthProvider.ENTRA, user -> {
            user.setEntraObjectId(objectId);
            user.setEntraTenantId(parseUuid(jwt.getClaimAsString("tid")));
        }));
    }


    /**
     * Binds a first-seen Entra identity to an account that matches by e-mail, or refuses.
     * Linking by e-mail requires ALL of: the row is not bound to an Entra object id yet; the token
     * carries an {@code oid}; its {@code tid} is the configured tenant (or the account's entity
     * federates from that tenant) and matches any tenant already recorded on the row; the token
     * asserts a verified address ({@code xms_edov} / {@code email_verified}) or
     * {@code registerwerk.auth.link-by-email-without-verification} allows linking without one.
     * Otherwise the token resolves to no account and {@link IdentityRebindRefusedEvent} is
     * published; the sanctioned path is an operator's four-eyes identity reset.
     */
    private Optional<AppUser> linkEntra(AppUser user, Jwt jwt, UUID objectId, String email) {
        UUID tenantId = parseUuid(jwt.getClaimAsString("tid"));
        String presented = objectId == null ? null : objectId.toString();
        if (user.getEntraObjectId() != null) {
            return refuse(user, "ENTRA", user.getEntraObjectId().toString(), presented, "BOUND_TO_OTHER_IDENTITY");
        }
        if (objectId == null) {
            return refuse(user, "ENTRA", null, presented, "NO_OBJECT_ID");
        }
        java.util.Set<UUID> allowedTenants = new java.util.HashSet<>();
        if (configuredTenantId != null) {
            allowedTenants.add(configuredTenantId);
        }
        if (user.getLegalEntityId() != null) {
            entityPort.idpTenantOf(user.getLegalEntityId()).ifPresent(allowedTenants::add);
        }
        boolean lenient = authProperties.linkByEmailWithoutVerificationAllowed();
        if (allowedTenants.isEmpty() ? !lenient : (tenantId == null || !allowedTenants.contains(tenantId))) {
            return refuse(user, "ENTRA", null, presented, "TENANT_NOT_ALLOWED");
        }
        if (user.getEntraTenantId() != null && tenantId != null && !user.getEntraTenantId().equals(tenantId)) {
            return refuse(user, "ENTRA", null, presented, "TENANT_MISMATCH");
        }
        Boolean verified = assertedVerified(jwt, "xms_edov", "email_verified");
        if (Boolean.FALSE.equals(verified) || (verified == null && !lenient)) {
            return refuse(user, "ENTRA", null, presented, verified == null ? "EMAIL_NOT_VERIFIED" : "EMAIL_UNVERIFIED");
        }
        user.setEntraObjectId(objectId);
        user.setEntraTenantId(tenantId);
        user.setAuthProvider(UserAuthProvider.ENTRA);
        AppUser saved = appUserRepository.save(user);
        log.info("Bound Entra oid to existing account: email={} oid={}", email, objectId);
        eventPublisher.publishEvent(new IdentityBoundEvent(saved.getId(), "ENTRA", null, presented,
                tenantId == null ? null : tenantId.toString(), "EMAIL"));
        return Optional.of(saved);
    }

    private Optional<AppUser> linkOidc(AppUser user, String subject, Jwt jwt) {
        if (user.getExternalSubject() != null) {
            return refuse(user, "OIDC", user.getExternalSubject(), subject, "BOUND_TO_OTHER_IDENTITY");
        }
        if (subject == null) {
            return refuse(user, "OIDC", null, null, "NO_SUBJECT");
        }
        Boolean verified = assertedVerified(jwt, "email_verified");
        if (Boolean.FALSE.equals(verified)
                || (verified == null && !authProperties.linkByEmailWithoutVerificationAllowed())) {
            return refuse(user, "OIDC", null, subject, verified == null ? "EMAIL_NOT_VERIFIED" : "EMAIL_UNVERIFIED");
        }
        user.setExternalSubject(subject);
        user.setAuthProvider(UserAuthProvider.OIDC);
        AppUser saved = appUserRepository.save(user);
        eventPublisher.publishEvent(new IdentityBoundEvent(saved.getId(), "OIDC", null, subject, null, "EMAIL"));
        return Optional.of(saved);
    }

    private Optional<AppUser> refuse(AppUser user, String provider, String boundId, String presentedId, String reason) {
        String key = user.getId() + "|" + presentedId + "|" + reason;
        if (refusalsAnnounced.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
            log.warn("Identity link refused for account {} ({}): {} bound={} presented={}",
                    user.getId(), provider, reason, boundId, presentedId);
            IdentityRebindRefusedEvent event = new IdentityRebindRefusedEvent(user.getId(), provider, boundId, presentedId, reason);
            newTx.executeWithoutResult(status -> eventPublisher.publishEvent(event));
        }
        return Optional.empty();
    }

    /** TRUE/FALSE when one of the claims asserts it, null when none is present. Accepts boolean, "true"/"false", "1"/"0". */
    private static Boolean assertedVerified(Jwt jwt, String... claimNames) {
        for (String name : claimNames) {
            Object v = jwt.getClaim(name);
            if (v == null) continue;
            if (v instanceof Boolean b) return b;
            String t = v.toString().trim();
            if (t.equals("1") || t.equalsIgnoreCase("true")) return Boolean.TRUE;
            return Boolean.FALSE;
        }
        return null;
    }

    /** Keeps the mirrored identity columns current without touching roles or enabled state. */
    private AppUser touch(AppUser user, Jwt jwt, UUID objectId) {
        boolean dirty = false;
        // entra_object_id is never overwritten here: the row was found BY this oid, or it was bound
        // through linkEntra (6-05).
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
                return linkOidc(byEmail.get(), subject, jwt);
            }
        }

        if (email == null) {
            log.warn("OIDC token for sub={} carries no email claim and no existing account matches — "
                    + "cannot provision (app_user.email is required)", subject);
            return Optional.empty();
        }

        return Optional.of(provisionDisabled(jwt, email, UserAuthProvider.OIDC,
                user -> user.setExternalSubject(subject)));
    }

    /** Keeps the mirrored identity column current without touching roles or enabled state. */
    private AppUser touchOidc(AppUser user, String subject) {
        boolean dirty = false;
        if (user.getAuthProvider() != UserAuthProvider.OIDC) {
            user.setAuthProvider(UserAuthProvider.OIDC);
            dirty = true;
        }
        return dirty ? appUserRepository.save(user) : user;
    }

    /**
     * Creates a disabled, roleless account for a principal the configured IdP has authenticated
     * but this app has never seen. {@code applyIdentity} sets the provider-specific identity
     * columns (Entra's {@code oid}/{@code tid}, or OIDC's {@code externalSubject}) before the
     * shared fields are filled in and the account saved.
     *
     * <p>Roles are explicitly set to empty rather than left at {@link AppUser}'s default — that
     * default seeds {@code REGISTRY_ADMIN} for the bundled seed admin, so leaving this unset would
     * provision every first-time IdP sign-in as a registry admin. Operator approval assigns
     * least-privilege roles through {@code OperatorUserService} / {@code CompanyUserService}.
     */
    private AppUser provisionDisabled(Jwt jwt, String email, UserAuthProvider provider,
                                       Consumer<AppUser> applyIdentity) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setFullName(firstNonBlank(jwt.getClaimAsString("name"), email));
        applyIdentity.accept(user);
        user.setAuthProvider(provider);
        user.setEnabled(false);
        user.setLastLoginAt(Instant.now());
        user.setLegalEntityId(parseUuid(claim(jwt, "entity_id", "entityId")));
        user.setRoles(Set.of());

        AppUser saved = appUserRepository.save(user);
        eventPublisher.publishEvent(new OidcUserProvisionedEvent(
                saved.getId(), saved.getEmail(), saved.getAuthProvider().name()));
        log.info("Provisioned disabled account for {} principal: id={} email={}",
                provider, saved.getId(), email);
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
