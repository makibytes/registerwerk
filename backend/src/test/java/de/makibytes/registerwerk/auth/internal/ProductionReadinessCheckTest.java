package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.shared.DemoOnly;
import de.makibytes.registerwerk.shared.ProductionMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ProductionReadinessCheck (Phase 7: 7A-01, 7A-02, 7A-12, 7B-08)")
class ProductionReadinessCheckTest {

    private AppUserRepository users;
    private PasswordEncoder encoder;
    private RegisterwerkAuthProperties props;
    private MockEnvironment env;
    private DefaultListableBeanFactory beans;
    private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        encoder = mock(PasswordEncoder.class);
        props = new RegisterwerkAuthProperties();
        props.setDevSecret("0123456789-0123456789-0123456789-xyz");
        props.getDefaultAdmin().setEmail("root@example.org");
        props.getDefaultAdmin().setPassword("a-real-password-9");
        env = new MockEnvironment()
                .withProperty("REGISTERWERK_PRODUCTION_MODE", "true")
                .withProperty("spring.datasource.password", "s3cret-db-password")
                .withProperty("registerwerk.cors.allowed-origins", "https://app.example.org")
                .withProperty("registerwerk.wallet.hsm.profile", "THALES")
                .withProperty("registerwerk.wallet.hsm.pin", "987654");
        beans = new DefaultListableBeanFactory();
        events = mock(ApplicationEventPublisher.class);
        when(users.countEnabledTotpEnrolledUsersWithRole(any())).thenReturn(2L);
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
    }

    private ProductionReadinessCheck check() {
        return check(true);
    }

    private ProductionReadinessCheck check(boolean hsmEnabled) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SeededAdminPolicy policy = new SeededAdminPolicy(props, users, encoder, registry, env);
        return new ProductionReadinessCheck(props, "", "AWS_KMS", hsmEnabled, false,
                "", "", "", false, "", users, encoder, env, beans, events, policy, registry);
    }

    @Test
    @DisplayName("production + seed-demo-data=true is refused; non-production still boots")
    void seedDemoDataRefused() {
        env.setProperty("registerwerk.seed-demo-data", "true");
        assertThatThrownBy(() -> check().check()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEED_DEMO_DATA");
        env.setProperty("REGISTERWERK_PRODUCTION_MODE", "false");
        assertThatCode(() -> check().check()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("production + demo-instant-settlement=true is refused")
    void demoInstantSettlementRefused() {
        env.setProperty("registerwerk.trading.demo-instant-settlement", "true");
        assertThatThrownBy(() -> check().check()).hasMessageContaining("demo-instant-settlement");
    }

    @Test
    @DisplayName("production + a DemoOnly bean is refused")
    void demoBeanRefused() {
        beans.registerSingleton("someDemoSeeder", new DemoOnly() { });
        assertThatThrownBy(() -> check().check()).hasMessageContaining("Demo seeder beans");
    }

    @Test
    @DisplayName("production mode set as property (-D / config source) is honoured, not only the OS env")
    void propertyHonoured() {
        env.getPropertySources().remove("test");
        MockEnvironment e = new MockEnvironment().withProperty("registerwerk.production-mode", "true");
        assertThat(ProductionMode.of(e).enabled()).isTrue();
        assertThat(ProductionMode.resolve(new MockEnvironment())).isFalse();
    }

    @Test
    @DisplayName("production + weak DB password is refused")
    void weakDbPassword() {
        env.setProperty("spring.datasource.password", "changeme");
        assertThatThrownBy(() -> check().check()).hasMessageContaining("DB_PASSWORD");
        env.setProperty("spring.datasource.password", "");
        assertThatThrownBy(() -> check().check()).hasMessageContaining("DB_PASSWORD");
    }

    @Test
    @DisplayName("production + published default for the runtime or migrator DB login is refused (T6-17)")
    void weakSplitLoginPasswords() {
        env.setProperty("spring.datasource.password", "registerwerk_app");
        assertThatThrownBy(() -> check().check()).hasMessageContaining("DB_PASSWORD");
        env.setProperty("spring.datasource.password", "a-long-random-runtime-secret-9f3");
        env.setProperty("spring.flyway.user", "registerwerk");
        env.setProperty("spring.flyway.password", "changeme");
        assertThatThrownBy(() -> check().check()).hasMessageContaining("SPRING_FLYWAY_PASSWORD");
        env.setProperty("spring.flyway.password", "");
        assertThatThrownBy(() -> check().check()).hasMessageContaining("SPRING_FLYWAY_PASSWORD");
        env.setProperty("spring.datasource.username", "same-login");
        env.setProperty("spring.flyway.user", "same-login");
        env.setProperty("spring.flyway.password", "a-long-random-migrator-secret-71c");
        assertThatThrownBy(() -> check().check()).hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("production + 31-byte JWT dev secret is refused (HS256 mode)")
    void shortJwtSecret() {
        props.setDevSecret("0123456789-0123456789-012345");
        props.setDevSecret("x".repeat(31));
        assertThatThrownBy(() -> check().check()).hasMessageContaining("JWT_DEV_SECRET");
    }

    @Test
    @DisplayName("production + all-localhost CORS origins is refused")
    void localhostCors() {
        env.setProperty("registerwerk.cors.allowed-origins", "http://localhost:44200,http://127.0.0.1:44201");
        assertThatThrownBy(() -> check().check()).hasMessageContaining("localhost");
        assertThat(ProductionReadinessCheck.allOriginsLocal("https://a.example.org,http://localhost:1")).isFalse();
    }

    @Test
    @DisplayName("production + published default secrets are refused")
    void defaultSecrets() {
        props.getDefaultAdmin().setPassword(ProductionReadinessCheck.DEFAULT_ADMIN_PASSWORD);
        assertThatThrownBy(() -> check().check()).hasMessageContaining("DEFAULT_ADMIN_PASSWORD");
        props.getDefaultAdmin().setPassword("a-real-password-9");
        env.setProperty("registerwerk.zama.relayer-api-key", ProductionReadinessCheck.DEFAULT_RELAYER_KEY);
        assertThatThrownBy(() -> check().check()).hasMessageContaining("ZAMA_RELAYER_API_KEY");
        env.setProperty("registerwerk.zama.relayer-api-key", "unique");
        env.setProperty("registerwerk.chaincache.jwt-secret", ProductionReadinessCheck.DEFAULT_CHAINCACHE_SECRET);
        assertThatThrownBy(() -> check().check()).hasMessageContaining("CHAINCACHE_JWT_SECRET");
    }

    @Test
    @DisplayName("seeded admin: sole admin is fatal; with another own-password admin it is degraded and login refused")
    void seededAdminPolicy() {
        AppUser seeded = new AppUser();
        UUID id = UUID.randomUUID();
        seeded.setId(id);
        seeded.setEmail("root@example.org");
        seeded.setEnabled(true);
        seeded.setMustChangePassword(true);
        seeded.setPasswordHash("h");
        org.springframework.test.util.ReflectionTestUtils.setField(seeded, "createdAt", Instant.now().minusSeconds(48 * 3600));
        when(users.findByEmailIgnoreCase("root@example.org")).thenReturn(Optional.of(seeded));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SeededAdminPolicy policy = new SeededAdminPolicy(props, users, encoder, registry, env);

        when(users.countEnabledWithOwnPasswordExcluding(AppUserRole.REGISTRY_ADMIN, id)).thenReturn(0L);
        assertThat(policy.evaluate()).isEqualTo(SeededAdminPolicy.State.FATAL);
        assertThat(policy.refusesLogin(seeded)).isFalse();

        when(users.countEnabledWithOwnPasswordExcluding(AppUserRole.REGISTRY_ADMIN, id)).thenReturn(1L);
        assertThat(policy.evaluate()).isEqualTo(SeededAdminPolicy.State.DEGRADED);
        assertThat(registry.get("registerwerk_seeded_admin_default_credentials").gauge().value()).isEqualTo(1.0);
        assertThat(policy.refusesLogin(seeded)).isTrue();
    }

    @Test
    @DisplayName("SoftHSM in production needs the explicit acknowledgement; demo PIN is refused")
    void softHsm() {
        env.setProperty("registerwerk.wallet.hsm.profile", "SOFTHSM");
        Mockito.reset(users);
        // HSM gate is evaluated after the wallet-key presence check which reads the OS env; exercise it via reflection.
        ProductionReadinessCheck c = check();
        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(c, "checkHsm"))
                .hasMessageContaining("SOFTHSM");
        env.setProperty("registerwerk.wallet.hsm.allow-software-token", "true");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(c, "checkHsm");
        c.acknowledgeSoftwareHsm();
        Mockito.verify(events).publishEvent(any(de.makibytes.registerwerk.auth.events.SoftwareHsmAcknowledgedEvent.class));
        env.setProperty("registerwerk.wallet.hsm.profile", "THALES");
        env.setProperty("registerwerk.wallet.hsm.pin", "123456");
        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(c, "checkHsm"))
                .hasMessageContaining("PIN");
    }

    @Test
    @DisplayName("T7-05: a cloud-KMS signer is a valid production custody target; without it PKCS#11 is still required")
    void kmsSignerSatisfiesCustodyGate() {
        // no HSM, no KMS: refused, and the message points at both options
        assertThatThrownBy(() -> check(false).checkSigningCustody())
                .hasMessageContaining("REGISTERWERK_HSM_ENABLED").hasMessageContaining("registerwerk.wallet.signer=kms");
        // KMS signer: no PKCS#11 token (and so no allow-software-token acknowledgement) needed, even with
        // the demo SoftHSM profile left at its default
        env.setProperty("registerwerk.wallet.signer", "kms");
        env.setProperty("registerwerk.wallet.hsm.profile", "SOFTHSM");
        env.setProperty("registerwerk.wallet.hsm.pin", "123456");
        assertThatCode(() -> check(false).checkSigningCustody()).doesNotThrowAnyException();
        // a PKCS#11 token that is ALSO enabled is still held to the SoftHSM / demo-PIN rules
        assertThatThrownBy(() -> check(true).checkSigningCustody()).hasMessageContaining("SOFTHSM");
        // 'software' (or blank) is today's behaviour
        env.setProperty("registerwerk.wallet.signer", "software");
        assertThatThrownBy(() -> check(false).checkSigningCustody()).hasMessageContaining("REGISTERWERK_HSM_ENABLED");
    }

    @Test
    @DisplayName("AuthService refuses the stale seeded account's password login while another admin can recover it")
    void authServiceRefusesSeededLogin() {
        AppUser seeded = new AppUser();
        UUID id = UUID.randomUUID();
        seeded.setId(id);
        seeded.setEmail("root@example.org");
        seeded.setEnabled(true);
        seeded.setMustChangePassword(true);
        seeded.setPasswordHash("h");
        seeded.setAuthProvider(de.makibytes.registerwerk.auth.api.UserAuthProvider.LOCAL);
        org.springframework.test.util.ReflectionTestUtils.setField(seeded, "createdAt", Instant.now().minusSeconds(48 * 3600));
        when(users.findByEmailIgnoreCase("root@example.org")).thenReturn(Optional.of(seeded));
        when(users.countEnabledWithOwnPasswordExcluding(AppUserRole.REGISTRY_ADMIN, id)).thenReturn(1L);
        when(encoder.matches(any(), any())).thenReturn(true);
        var limiter = mock(LoginAttemptLimiter.class);
        when(limiter.check(any(), any())).thenReturn(LoginAttemptLimiter.Decision.OPEN);
        SeededAdminPolicy policy = new SeededAdminPolicy(props, users, encoder, new SimpleMeterRegistry(), env);
        AuthService auth = new AuthService(users, encoder, mock(de.makibytes.registerwerk.auth.api.JwtMintingService.class),
                props, limiter, policy);

        assertThatThrownBy(() -> auth.login("root@example.org", "whatever", "10.0.0.1"))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidCredentialsException.class);
    }
}
