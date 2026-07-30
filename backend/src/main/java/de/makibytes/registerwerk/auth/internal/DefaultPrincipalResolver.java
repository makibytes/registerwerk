package de.makibytes.registerwerk.auth.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.PrincipalResolver;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Roles come from the {@code app_user} row, not from the token's {@code roles} claim. Entra
 * app roles are consulted only when provisioning a brand-new account; after that, role changes
 * are made through {@code OperatorUserService} / {@code CompanyUserService} and Entra's copy is
 * ignored. That keeps a single authority for authorisation, and means an operator can revoke a
 * role without waiting for a token to expire.
 */
@Component
class DefaultPrincipalResolver implements PrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultPrincipalResolver.class);

    private final AppUserRepository appUserRepository;

    DefaultPrincipalResolver(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
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
        return resolveEntraPrincipal(jwt);
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
     * <p>The new account is created <em>enabled</em>, because Entra has already authenticated
     * them and Conditional Access has already applied — refusing entry here would just be a
     * second, unmanaged access-control layer. Roles come from the token's app-role assignment
     * this once; thereafter the DB row is authoritative. A principal with no recognised app
     * role gets an account with no roles, which authenticates but authorises nothing.
     */
    private AppUser provision(Jwt jwt, UUID objectId, String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setFullName(firstNonBlank(jwt.getClaimAsString("name"), email));
        user.setEntraObjectId(objectId);
        user.setEntraTenantId(parseUuid(jwt.getClaimAsString("tid")));
        user.setAuthProvider(UserAuthProvider.ENTRA);
        user.setEnabled(true);
        user.setLastLoginAt(Instant.now());
        user.setLegalEntityId(parseUuid(claim(jwt, "entity_id", "entityId")));

        Set<AppUserRole> roles = mapRoles(jwt.getClaimAsStringList("roles"));
        if (!roles.isEmpty()) {
            user.setRoles(roles);
        }

        AppUser saved = appUserRepository.save(user);
        log.info("Provisioned account for Entra principal: id={} oid={} email={} roles={}",
                saved.getId(), objectId, email, roles);
        return saved;
    }

    private static boolean isLocallyMinted(Jwt jwt) {
        return JwtMintingService.LOCAL_ISSUER.equals(jwt.getClaimAsString("iss"));
    }

    private static Set<AppUserRole> mapRoles(List<String> claimed) {
        EnumSet<AppUserRole> mapped = EnumSet.noneOf(AppUserRole.class);
        if (claimed == null) {
            return mapped;
        }
        for (String role : claimed) {
            try {
                mapped.add(AppUserRole.valueOf(role));
            } catch (IllegalArgumentException ignored) {
                log.debug("Ignoring unmapped role from token: {}", role);
            }
        }
        return mapped;
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
