package de.makibytes.registerwerk.auth.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.EntityActivityPort;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.events.IdentityBoundEvent;
import de.makibytes.registerwerk.auth.events.IdentityRebindRefusedEvent;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.events.OidcUserProvisionedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
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
    @Mock private ApplicationEventPublisher eventPublisher;

    private DefaultPrincipalResolver resolver;
    private RegisterwerkAuthProperties authProps;

    private final UUID appUserId = UUID.randomUUID();
    private final UUID entraOid = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        authProps = new RegisterwerkAuthProperties();
        authProps.setLinkByEmailWithoutVerification(true);
        resolver = new DefaultPrincipalResolver(repository, eventPublisher, authProps, new EntityActivityPort() {
            @Override public boolean isTerminated(UUID entityId) { return false; }
        }, tenantId.toString(), new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
        });
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
    @DisplayName("an unknown Entra principal is provisioned disabled with no roles")
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
        assertThat(created.isEnabled()).isFalse();
        assertThat(created.getRoles()).isEmpty();

        verify(eventPublisher).publishEvent(any(OidcUserProvisionedEvent.class));
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

    @Test
    @DisplayName("a non-Entra OIDC token (no oid claim) resolves by sub, not email")
    void oidcToken_resolvesBySubject() {
        AppUser existing = account();
        existing.setExternalSubject("okta-user-42");
        existing.setAuthProvider(UserAuthProvider.OIDC);
        when(repository.findByExternalSubject("okta-user-42")).thenReturn(Optional.of(existing));

        Optional<AppUser> resolved = resolver.resolve(auth(oidcJwt()));

        assertThat(resolved).contains(existing);
        verify(repository, never()).findByEntraObjectId(any());
    }

    @Test
    @DisplayName("an account created before external_subject existed is found by email and backfilled")
    void oidcToken_backfillsSubjectFromEmail() {
        AppUser legacy = account();
        when(repository.findByExternalSubject("okta-user-42")).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.of(legacy));
        when(repository.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        resolver.resolve(auth(oidcJwt()));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getExternalSubject()).isEqualTo("okta-user-42");
        assertThat(captor.getValue().getAuthProvider()).isEqualTo(UserAuthProvider.OIDC);
    }

    @Test
    @DisplayName("an unknown OIDC principal is provisioned disabled with no roles")
    void oidcToken_provisionsNewAccount() {
        when(repository.findByExternalSubject("okta-user-42")).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.empty());
        when(repository.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        Optional<AppUser> resolved = resolver.resolve(auth(oidcJwt()));

        assertThat(resolved).isPresent();
        AppUser created = resolved.get();
        assertThat(created.getExternalSubject()).isEqualTo("okta-user-42");
        assertThat(created.getEmail()).isEqualTo("customer@test.local");
        assertThat(created.getAuthProvider()).isEqualTo(UserAuthProvider.OIDC);
        assertThat(created.isEnabled()).isFalse();
        assertThat(created.getRoles()).isEmpty();

        verify(eventPublisher).publishEvent(any(OidcUserProvisionedEvent.class));
    }

    @Test
    @DisplayName("a subject-only OIDC token (no email claim, no existing match) cannot self-provision")
    void oidcToken_subjectOnlyWithNoMatch_isUnresolvable() {
        when(repository.findByExternalSubject("okta-user-42")).thenReturn(Optional.empty());

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://issuer.example.com/oauth2/default")
                .claim("sub", "okta-user-42")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(resolver.resolve(auth(jwt))).isEmpty();
    }

    private Jwt oidcJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://issuer.example.com/oauth2/default")
                .claim("sub", "okta-user-42")
                .claim("email", "customer@test.local")
                .claim("name", "Customer User")
                .claim("roles", List.of("INVESTOR"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private AppUser account() {
        AppUser u = new AppUser();
        u.setId(appUserId);
        u.setEmail("customer@test.local");
        u.setFullName("Customer User");
        u.setEnabled(true);
        u.setRoles(Set.of(AppUserRole.INVESTOR));
        return u;
    }

    private Jwt entraJwtFor(UUID oid, UUID tid, Object verified) {
        Jwt.Builder b = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claim("iss", "https://login.microsoftonline.com/" + tid + "/v2.0")
                .claim("sub", "entra-subject-not-a-uuid")
                .claim("oid", oid.toString())
                .claim("tid", tid.toString())
                .claim("preferred_username", "customer@test.local")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (verified != null) {
            b.claim("xms_edov", verified);
        }
        return b.build();
    }

    @Test
    @DisplayName("6-05: a row already bound to another oid is NOT re-pointed; the token resolves to nothing and the refusal is audited")
    void entraToken_boundRow_isNeverRebound() {
        UUID otherOid = UUID.randomUUID();
        AppUser bound = account();
        bound.setEntraObjectId(otherOid);
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.of(bound));

        assertThat(resolver.resolve(auth(entraJwtFor(entraOid, tenantId, true)))).isEmpty();

        assertThat(bound.getEntraObjectId()).isEqualTo(otherOid);
        verify(repository, never()).save(any());
        ArgumentCaptor<IdentityRebindRefusedEvent> ev = ArgumentCaptor.forClass(IdentityRebindRefusedEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().payload()).containsEntry("reason", "BOUND_TO_OTHER_IDENTITY");
    }

    @Test
    @DisplayName("6-05: without a verified-address assertion linking by e-mail is refused when the lenient flag is off")
    void entraToken_unverifiedEmail_isNotLinked() {
        authProps.setLinkByEmailWithoutVerification(false);
        AppUser legacy = account();
        legacy.setEntraObjectId(null);
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.of(legacy));

        assertThat(resolver.resolve(auth(entraJwtFor(entraOid, tenantId, null)))).isEmpty();
        assertThat(legacy.getEntraObjectId()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("6-05: a token asserting xms_edov=false is refused even in lenient mode")
    void entraToken_explicitlyUnverified_isNotLinked() {
        AppUser legacy = account();
        legacy.setEntraObjectId(null);
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.of(legacy));

        assertThat(resolver.resolve(auth(entraJwtFor(entraOid, tenantId, false)))).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("6-05: a verified token from the configured tenant binds an unbound row and publishes IdentityBoundEvent")
    void entraToken_verified_bindsAndAudits() {
        authProps.setLinkByEmailWithoutVerification(false);
        AppUser legacy = account();
        legacy.setEntraObjectId(null);
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.of(legacy));
        when(repository.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(resolver.resolve(auth(entraJwtFor(entraOid, tenantId, true)))).isPresent();
        assertThat(legacy.getEntraObjectId()).isEqualTo(entraOid);
        verify(eventPublisher).publishEvent(any(IdentityBoundEvent.class));
    }

    @Test
    @DisplayName("6-05: a token from a foreign tenant is not linked by e-mail")
    void entraToken_foreignTenant_isNotLinked() {
        AppUser legacy = account();
        legacy.setEntraObjectId(null);
        UUID foreign = UUID.randomUUID();
        when(repository.findByEntraObjectId(entraOid)).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.of(legacy));

        assertThat(resolver.resolve(auth(entraJwtFor(entraOid, foreign, true)))).isEmpty();
        assertThat(legacy.getEntraObjectId()).isNull();
    }

    @Test
    @DisplayName("6-05: an OIDC subject bound to another sub is not overwritten")
    void oidcToken_boundRow_isNeverRebound() {
        AppUser bound = account();
        bound.setExternalSubject("someone-else");
        when(repository.findByExternalSubject("okta-user-42")).thenReturn(Optional.empty());
        when(repository.findByEmailIgnoreCase("customer@test.local")).thenReturn(Optional.of(bound));

        assertThat(resolver.resolve(auth(oidcJwt()))).isEmpty();
        assertThat(bound.getExternalSubject()).isEqualTo("someone-else");
        verify(repository, never()).save(any());
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
