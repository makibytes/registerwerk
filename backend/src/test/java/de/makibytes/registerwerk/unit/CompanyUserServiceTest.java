package de.makibytes.registerwerk.unit;

import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.externalref.api.CompanyExternalReferenceService;
import de.makibytes.registerwerk.customer.internal.CompanyUserService;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserActionToken;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenType;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenRepository;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.web.dto.CompanyIdpSettingsRequest;
import de.makibytes.registerwerk.customer.web.dto.InviteCompanyUserRequest;
import de.makibytes.registerwerk.auth.web.dto.PublicPasswordResetCompleteRequest;
import de.makibytes.registerwerk.auth.web.dto.PublicUserRegistrationCompleteRequest;
import de.makibytes.registerwerk.customer.web.dto.UpdateCompanyUserRolesRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyUserService unit tests")
class CompanyUserServiceTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private AppUserActionTokenRepository actionTokenRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock private CompanyExternalReferenceService companyExternalReferenceService;

    private RegisterwerkAuthProperties authProperties;
    private CompanyUserService service;
    private UUID entityId;
    private UUID actorId;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        authProperties = new RegisterwerkAuthProperties();
        authProperties.setEntraEnabled(false);
        service = new CompanyUserService(
            appUserRepository,
            actionTokenRepository,
            legalEntityRepository,
            passwordEncoder,
            eventPublisher,
            authProperties,
            companyExternalReferenceService,
            "http://localhost:4201",
            48L
        );
        entityId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        authentication = buildAuthentication(entityId, actorId);
    }

    @Test
    @DisplayName("inviteUser creates a local user and emails a registration link")
    void inviteUser_createsLocalUserAndEmailsRegistrationLink() {
        LegalEntity entity = buildEntity();
        when(appUserRepository.findByEmailIgnoreCase("alice@test.local")).thenReturn(Optional.empty());
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(UUID.randomUUID());
            }
            return user;
        });

        service.inviteUser(authentication, new InviteCompanyUserRequest(
            "alice@test.local",
            "Alice Example",
            Set.of(AppUserRole.ISSUER, AppUserRole.COMPANY_ADMIN)
        ));

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(userCaptor.capture());
        AppUser savedUser = userCaptor.getValue();
        assertThat(savedUser.getLegalEntityId()).isEqualTo(entityId);
        assertThat(savedUser.getAuthProvider()).isEqualTo(UserAuthProvider.LOCAL);
        assertThat(savedUser.getRoles()).containsExactlyInAnyOrder(AppUserRole.ISSUER, AppUserRole.COMPANY_ADMIN);
        verify(actionTokenRepository).save(any(AppUserActionToken.class));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("updateUserRoles rejects removing COMPANY_ADMIN from the last enabled admin")
    void updateUserRoles_rejectsDemotingLastCompanyAdmin() {
        AppUser user = buildCompanyAdmin();
        when(appUserRepository.findByIdAndLegalEntityId(user.getId(), entityId)).thenReturn(Optional.of(user));
        when(appUserRepository.countEnabledUsersByLegalEntityIdAndRole(entityId, AppUserRole.COMPANY_ADMIN, user.getId()))
            .thenReturn(0L);

        assertThatThrownBy(() -> service.updateUserRoles(
            authentication,
            user.getId(),
            new UpdateCompanyUserRolesRequest(Set.of(AppUserRole.ISSUER))
        )).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("saveIdpSettings stores company IdP settings on the legal entity")
    void saveIdpSettings_updatesEntity() {
        LegalEntity entity = buildEntity();
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));

        service.saveIdpSettings(authentication, new CompanyIdpSettingsRequest("https://issuer.example", "client-123", "secret"));

        assertThat(entity.getIdpIssuerUrl()).isEqualTo("https://issuer.example");
        assertThat(entity.getIdpClientId()).isEqualTo("client-123");
        assertThat(entity.getIdpClientSecret()).isEqualTo("secret");
        verify(legalEntityRepository).save(entity);
    }

    @Test
    @DisplayName("completeRegistration sets the password and consumes the registration token")
    void completeRegistration_setsPasswordAndConsumesToken() {
        AppUser user = buildCompanyAdmin();
        user.setPasswordHash(null);
        AppUserActionToken token = buildActionToken(user.getId(), AppUserActionTokenType.REGISTRATION);
        when(actionTokenRepository.findByTokenHash(any(String.class))).thenReturn(Optional.of(token));
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("Sup3rSecret!")).thenReturn("encoded-password");

        service.completeRegistration(new PublicUserRegistrationCompleteRequest(tokenValue(), "Alice Example", "Sup3rSecret!"));

        assertThat(user.getFullName()).isEqualTo("Alice Example");
        assertThat(user.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(token.getConsumedAt()).isNotNull();
        verify(appUserRepository).save(user);
        verify(actionTokenRepository).save(token);
    }

    @Test
    @DisplayName("completePasswordReset updates the password and consumes the reset token")
    void completePasswordReset_updatesPassword() {
        AppUser user = buildCompanyAdmin();
        AppUserActionToken token = buildActionToken(user.getId(), AppUserActionTokenType.PASSWORD_RESET);
        when(actionTokenRepository.findByTokenHash(any(String.class))).thenReturn(Optional.of(token));
        when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("N3wSecret!")).thenReturn("new-encoded-password");

        service.completePasswordReset(new PublicPasswordResetCompleteRequest(tokenValue(), "N3wSecret!"));

        assertThat(user.getPasswordHash()).isEqualTo("new-encoded-password");
        assertThat(token.getConsumedAt()).isNotNull();
        verify(appUserRepository).save(user);
        verify(actionTokenRepository).save(token);
    }

    @Test
    @DisplayName("createInitialCompanyAdmin creates an Entra-managed company admin without a local password")
    void createInitialCompanyAdmin_createsEntraManagedAdmin() {
        when(appUserRepository.findByEmailIgnoreCase("admin@company.test")).thenReturn(Optional.empty());
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser created = service.createInitialCompanyAdmin(
            entityId,
            "admin@company.test",
            "Initial Admin",
            null,
            true
        );

        assertThat(created.getLegalEntityId()).isEqualTo(entityId);
        assertThat(created.getAuthProvider()).isEqualTo(UserAuthProvider.ENTRA);
        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.getRoles()).containsExactly(AppUserRole.COMPANY_ADMIN);
    }

    @Test
    @DisplayName("syncAuthenticatedPrincipal upserts the current Entra user from JWT claims")
    void syncAuthenticatedPrincipal_upsertsCurrentEntraUser() {
        authProperties.setEntraEnabled(true);
        when(appUserRepository.findByEmailIgnoreCase("company-admin@test.local")).thenReturn(Optional.empty());
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Authentication entraAuthentication = buildAuthentication(entityId, actorId, "company-admin@test.local", "Company Admin", List.of("COMPANY_ADMIN", "ISSUER"));

        service.syncAuthenticatedPrincipal(entraAuthentication);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(captor.capture());
        AppUser saved = captor.getValue();
        assertThat(saved.getAuthProvider()).isEqualTo(UserAuthProvider.ENTRA);
        assertThat(saved.getRoles()).containsExactlyInAnyOrder(AppUserRole.COMPANY_ADMIN, AppUserRole.ISSUER);
        assertThat(saved.getLastLoginAt()).isNotNull();
    }

    private AppUser buildCompanyAdmin() {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setEmail("admin@company.test");
        user.setFullName("Company Admin");
        user.setLegalEntityId(entityId);
        user.setAuthProvider(UserAuthProvider.LOCAL);
        user.setEnabled(true);
        user.setRoles(Set.of(AppUserRole.COMPANY_ADMIN));
        return user;
    }

    private LegalEntity buildEntity() {
        LegalEntity entity = new LegalEntity();
        entity.setId(entityId);
        entity.setCurrentName("Test Issuer GmbH");
        entity.setType(EntityType.ISSUER);
        entity.setStatus(EntityStatus.ACTIVE);
        return entity;
    }

    private AppUserActionToken buildActionToken(UUID userId, AppUserActionTokenType type) {
        AppUserActionToken token = new AppUserActionToken();
        token.setAppUserId(userId);
        token.setTokenHash(anyHash());
        token.setTokenType(type);
        token.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        return token;
    }

    private Authentication buildAuthentication(UUID entityId, UUID actorId) {
        return buildAuthentication(entityId, actorId, "company-admin@test.local", "Company Admin", List.of("COMPANY_ADMIN"));
    }

    private Authentication buildAuthentication(UUID entityId, UUID actorId, String email, String name, List<String> roles) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(actorId.toString())
            .claim("entity_id", entityId.toString())
            .claim("email", email)
            .claim("name", name)
            .claim("roles", roles)
            .build();
        return new JwtAuthenticationToken(jwt);
    }

    private String anyHash() {
        return "d5579c46dfcc7f18207013e65b44e4cb4e2c2298f4ac457ba8f82743f31e930b";
    }

    private String tokenValue() {
        return "token-value";
    }
}
