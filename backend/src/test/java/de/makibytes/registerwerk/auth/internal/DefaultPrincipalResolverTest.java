package de.makibytes.registerwerk.auth.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPrincipalResolver")
class DefaultPrincipalResolverTest {

    @Mock private AppUserRepository repository;

    private DefaultPrincipalResolver resolver;

    private final UUID appUserId = UUID.randomUUID();
    private final UUID entraOid = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new DefaultPrincipalResolver(repository);
    }

    @Test
    @DisplayName("a locally minted token resolves straight through sub")
    void localToken_resolvesBySub() {
        AppUser existing = account();
        when(repository.findById(appUserId)).thenReturn(Optional.of(existing));

        Optional<AppUser> resolved = resolver.resolve(auth(Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .claim("iss", JwtMintingService.LOCAL_ISSUER)
                .claim("sub", appUserId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build()));

        assertThat(resolved).contains(existing);
        verify(repository, never()).findByEntraObjectId(any());
    }

    @Test
    @DisplayName("an Entra token resolves by oid, not by the sub claim")
    void entraToken_resolvesByObjectId() {
        AppUser existing = account();
        existing.setEntraObjectId(entraOid);
        existing.setEntraTenantId(tenantId);
        existing.setAuthProvider(UserAuthProvider.ENTRA);
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.of(existing));

        Optional<AppUser> resolved = resolver.resolve(auth(entraJwt()));

        // The critical assertion: `sub` on an Entra token is Entra's identifier and matches no
        // row. Resolving through it is exactly the bug this class exists to fix.
        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo(appUserId);
    }

    @Test
    @DisplayName("an account created before entra_object_id existed is found by email and backfilled")
    void entraToken_backfillsObjectIdFromEmail() {
        AppUser legacy = account();
        legacy.setEntraObjectId(null);
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.of(legacy));
        when(repository.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolve(auth(entraJwt()));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEntraObjectId()).isEqualTo(entraOid);
        assertThat(captor.getValue().getEntraTenantId()).isEqualTo(tenantId);
        assertThat(captor.getValue().getAuthProvider()).isEqualTo(UserAuthProvider.ENTRA);
    }

    @Test
    @DisplayName("an unknown Entra principal is provisioned with the token's app roles")
    void entraToken_provisionsNewAccount() {
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.empty());
        when(repository.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        Optional<AppUser> resolved = resolver.resolve(auth(entraJwt()));

        assertThat(resolved).isPresent();
        AppUser created = resolved.get();
        assertThat(created.getEntraObjectId()).isEqualTo(entraOid);
        assertThat(created.getEmail()).isEqualTo("customer@test.local");
        assertThat(created.getAuthProvider()).isEqualTo(UserAuthProvider.ENTRA);
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getRoles()).containsExactly(AppUserRole.INVESTOR);
    }

    @Test
    @DisplayName("unrecognised app roles are dropped rather than failing the sign-in")
    void entraToken_ignoresUnknownRoles() {
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.empty());
        when(repository.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0")
                .claim("sub", "entra-subject-not-a-uuid")
                .claim("oid", entraOid.toString())
                .claim("tid", tenantId.toString())
                .claim("preferred_username", "customer@test.local")
                .claim("roles", List.of("INVESTOR", "SomeAzureGroupName"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        Optional<AppUser> resolved = resolver.resolve(auth(jwt));

        assertThat(resolved.orElseThrow().getRoles()).containsExactly(AppUserRole.INVESTOR);
    }

    @Test
    @DisplayName("a token with neither oid nor email resolves to nothing")
    void entraToken_withoutIdentifiers_isUnresolvable() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0")
                .claim("sub", "opaque")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(resolver.resolve(auth(jwt))).isEmpty();
    }

    @Test
    @DisplayName("requireUser fails closed when nothing resolves")
    void requireUser_failsClosed() {
        Authentication authentication = auth(Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0")
                .claim("sub", "opaque")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build());

        assertThatThrownBy(() -> resolver.requireUser(authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AppUser account() {
        AppUser u = new AppUser();
        u.setId(appUserId);
        u.setEmail("customer@test.local");
        u.setFullName("Customer User");
        u.setEnabled(true);
        u.setRoles(Set.of(AppUserRole.INVESTOR));
        return u;
    }

    private Jwt entraJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://login.microsoftonline.com/" + tenantId + "/v2.0")
                .claim("sub", "entra-subject-not-a-uuid")
                .claim("oid", entraOid.toString())
                .claim("tid", tenantId.toString())
                .claim("preferred_username", "customer@test.local")
                .claim("name", "Customer User")
                .claim("roles", List.of("INVESTOR"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static Authentication auth(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
