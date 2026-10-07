package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies the TOTP enrolment flow  — previously no code path existed by
 * which a real user could ever enrol, meaning every {@code @RequiresStepUp} endpoint was
 * permanently unreachable in a production deployment.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepUpTokenIssuer TOTP enrolment unit tests")
class StepUpTokenIssuerEnrollmentTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private TotpStateRepository state;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ApplicationEventPublisher events;

    private static final KekProvider XOR_KEK = new KekProvider() {
        @Override public String name() { return "TEST-KEK"; }
        @Override public byte[] wrap(byte[] dek) { return xor(dek); }
        @Override public byte[] unwrap(byte[] wrapped) { return xor(wrapped); }
        private byte[] xor(byte[] in) {
            byte[] out = in.clone();
            for (int i = 0; i < out.length; i++) out[i] ^= (byte) 0x5A;
            return out;
        }
    };

    /** Records whether a transaction (= a held pooled connection) is open at any moment. */
    private final java.util.concurrent.atomic.AtomicBoolean txOpen = new java.util.concurrent.atomic.AtomicBoolean();
    private final org.springframework.transaction.PlatformTransactionManager txManager =
            new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition d) { txOpen.set(true); }
                @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { txOpen.set(false); }
                @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { txOpen.set(false); }
            };

    private StepUpTokenIssuer issuer;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        props.setDevSecret("test-secret-at-least-32-bytes-long!!");
        issuer = new StepUpTokenIssuer(userRepository, new JwtMintingService(props), new TotpSecretStore(XOR_KEK),
                state, passwordEncoder, new DualControlProperties(), events, false, txManager);
        lenient().when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        lenient().when(state.acceptStep(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        lenient().when(state.reserveAttempt(any())).thenReturn(true);
    }

    private AppUser freshUser() {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setEmail("admin@test.local");
        user.setPasswordHash("hash");
        return user;
    }

    /** The cleartext secret of a user whose stored value is the envelope ciphertext. */
    private String plainSecretOf(AppUser user) {
        return new TotpSecretStore(XOR_KEK).decrypt(userId, user.getTotpSecret());
    }

    @Test
    @DisplayName("enroll generates and stores a secret, but does not activate TOTP yet")
    void enroll_generatesSecretWithoutActivating() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        StepUpTokenIssuer.EnrollmentStart start = issuer.enroll(userId, "pw");

        assertThat(start.secret()).isNotBlank();
        assertThat(start.otpauthUri()).contains(start.secret()).contains("otpauth://totp/Registerwerk");
        assertThat(user.isTotpEnabled()).isFalse();
    }

    @Test
    @DisplayName("K3 6-09: the stored secret is envelope ciphertext, never the cleartext secret")
    void enroll_storesEncryptedSecret() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        StepUpTokenIssuer.EnrollmentStart start = issuer.enroll(userId, "pw");

        assertThat(user.getTotpSecret()).startsWith("enc:v1:").doesNotContain(start.secret());
        assertThat(plainSecretOf(user)).isEqualTo(start.secret());
        assertThat(user.getTotpSecretKid()).isEqualTo("TEST-KEK");
    }

    @Test
    @DisplayName("K3 6-09: enrolment without the current password is refused; the attempt was reserved first and is never given back (no trust on first use)")
    void enroll_requiresCurrentPassword() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> issuer.enroll(userId, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> issuer.enroll(userId, "wrong")).isInstanceOf(AccessDeniedException.class);

        assertThat(user.getTotpSecret()).isNull();
        org.mockito.Mockito.verify(state, org.mockito.Mockito.times(2)).reserveAttempt(userId);
        org.mockito.Mockito.verify(state, org.mockito.Mockito.never()).releaseAttempt(userId);
    }

    @Test
    @DisplayName("C4: a correct password gives its reservation back, so enrolling does not eat into the attempt budget")
    void enroll_releasesReservationOnSuccess() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        issuer.enroll(userId, "pw");

        org.mockito.Mockito.verify(state).reserveAttempt(userId);
        org.mockito.Mockito.verify(state).releaseAttempt(userId);
    }

    @Test
    @DisplayName("C4/H14: the attempt is reserved before the code is compared; a locked account never reaches the comparison")
    void verify_reservesBeforeComparing() {
        AppUser user = enrolledAdmin(StepUpTokenIssuer.generateBase32Secret());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(state.reserveAttempt(userId)).thenReturn(false);
        long step = Instant.now().getEpochSecond() / 30;

        assertThatThrownBy(() -> issuer.issueAfterVerification(userId, "123456", "TOTP"))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("Too many");
        org.mockito.Mockito.verify(state, org.mockito.Mockito.never())
                .acceptStep(any(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(step).isPositive();
    }

    @Test
    @DisplayName("K3 6-09: an IdP-managed account cannot enrol a local authenticator")
    void enroll_refusedForIdpManagedAccount() {
        AppUser user = freshUser();
        user.setAuthProvider(UserAuthProvider.ENTRA);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> issuer.enroll(userId, "pw"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("identity provider");
    }

    @Test
    @DisplayName("K3 6-09: a locked-out account cannot be used to guess the password via enrol")
    void enroll_refusedWhileLocked() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(state.reserveAttempt(userId)).thenReturn(false);

        assertThatThrownBy(() -> issuer.enroll(userId, "pw")).isInstanceOf(AccessDeniedException.class);
        org.mockito.Mockito.verify(passwordEncoder, org.mockito.Mockito.never()).matches(any(), any());
    }

    @Test
    @DisplayName("K3 6-09: confirm records the accepted time step, so the enrolment code cannot be replayed as a step-up code")
    void confirm_recordsAcceptedStep() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        StepUpTokenIssuer.EnrollmentStart start = issuer.enroll(userId, "pw");
        long step = Instant.now().getEpochSecond() / 30;

        issuer.confirmEnrollment(userId, StepUpTokenIssuer.generateTotp(start.secret(), step));

        org.mockito.Mockito.verify(state).acceptStep(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.longThat(accepted -> Math.abs(accepted - step) <= 1));
    }

    @Test
    @DisplayName("K3 6-09: a code at or before the last accepted step (replay on any replica) is rejected and counted")
    void verify_replayRejectedViaSharedState() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        StepUpTokenIssuer.EnrollmentStart start = issuer.enroll(userId, "pw");
        long step = Instant.now().getEpochSecond() / 30;
        when(state.acceptStep(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);

        assertThatThrownBy(() -> issuer.confirmEnrollment(userId, StepUpTokenIssuer.generateTotp(start.secret(), step)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("already used");
        org.mockito.Mockito.verify(state, org.mockito.Mockito.atLeastOnce()).reserveAttempt(userId);
        assertThat(user.isTotpEnabled()).isFalse();
    }

    @Test
    @DisplayName("H14: a plaintext TOTP secret is never accepted at verification time - the startup migration is the only path off plaintext")
    void plaintextSecret_isRefused() {
        AppUser user = freshUser();
        String legacy = StepUpTokenIssuer.generateBase32Secret();
        user.setTotpSecret(legacy);
        user.setTotpEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        long step = Instant.now().getEpochSecond() / 30;

        assertThatThrownBy(() -> issuer.issueAfterVerification(userId, StepUpTokenIssuer.generateTotp(legacy, step), "TOTP"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(user.getTotpSecret()).as("verification must not quietly rewrite it either").isEqualTo(legacy);
    }

    @Test
    @DisplayName("K3 6-09: disenrol requires a valid current code, clears the enrolment and revokes sessions")
    void disenroll_requiresCurrentCode() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        StepUpTokenIssuer.EnrollmentStart start = issuer.enroll(userId, "pw");
        issuer.confirmEnrollment(userId, StepUpTokenIssuer.generateTotp(start.secret(), Instant.now().getEpochSecond() / 30));

        assertThatThrownBy(() -> issuer.disenroll(userId, "000000")).isInstanceOf(AccessDeniedException.class);
        assertThat(user.isTotpEnabled()).isTrue();

        issuer.disenroll(userId, StepUpTokenIssuer.generateTotp(start.secret(), Instant.now().getEpochSecond() / 30));

        assertThat(user.isTotpEnabled()).isFalse();
        assertThat(user.getTotpSecret()).isNull();
        assertThat(user.getTokensValidAfter()).isNotNull();
        org.mockito.Mockito.verify(state).reset(userId);
    }

    @Test
    @DisplayName("K3 6-09: operator reset clears the enrolment and audits both identities; self-reset is refused")
    void reset_byOperator() {
        AppUser user = freshUser();
        user.setTotpSecret("x");
        user.setTotpEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UUID actor = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        issuer.reset(userId, actor, "REGISTRY_ADMIN", approver);

        assertThat(user.isTotpEnabled()).isFalse();
        assertThat(user.getTotpSecret()).isNull();
        org.mockito.ArgumentCaptor<Object> ev = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(events).publishEvent(ev.capture());
        var event = (de.makibytes.registerwerk.stepup.events.TotpLifecycleEvent) ev.getValue();
        assertThat(event.eventType()).isEqualTo("TOTP_RESET");
        assertThat(event.dualControlApproverId()).isEqualTo(approver);
        assertThat(event.actorId()).isEqualTo(actor);

        assertThatThrownBy(() -> issuer.reset(userId, userId, "REGISTRY_ADMIN", approver))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("K3 6-08: a dual-control approval token needs a target, and carries scope, target digest and a jti")
    void approvalToken_boundToTarget() {
        AppUser user = freshUser();
        String secret = StepUpTokenIssuer.generateBase32Secret();
        user.setTotpSecret(new TotpSecretStore(XOR_KEK).encrypt(userId, secret));
        user.setTotpEnabled(true);
        user.setRoles(java.util.Set.of(de.makibytes.registerwerk.auth.api.AppUserRole.REGISTRY_ADMIN));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        String code = StepUpTokenIssuer.generateTotp(secret, Instant.now().getEpochSecond() / 30);

        assertThatThrownBy(() -> issuer.issueAfterVerification(userId, code, "TOTP", "FORCE_BURN_EWG26"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("target");

        String token = issuer.issueAfterVerification(userId, code, "TOTP", "FORCE_BURN_EWG26",
                "POST /api/v1/x/1", null);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).contains("\"stepup_scope\":\"FORCE_BURN_EWG26\"")
                .contains("\"stepup_target\":\"" + de.makibytes.registerwerk.stepup.api.DualControlTarget
                        .digestOfTarget("POST /api/v1/x/1", "") + "\"")
                .contains("\"jti\"");
    }

    private AppUser enrolledAdmin(String secret) {
        AppUser user = freshUser();
        user.setTotpSecret(new TotpSecretStore(XOR_KEK).encrypt(userId, secret));
        user.setTotpEnabled(true);
        user.setRoles(java.util.Set.of(de.makibytes.registerwerk.auth.api.AppUserRole.REGISTRY_ADMIN));
        return user;
    }

    private static String payloadOf(String token) {
        return new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("C1: an approval token is marked use=dual_control with its own audience; an ordinary step-up token is not")
    void approvalToken_isMarkedDualControlOnly() {
        String secret = StepUpTokenIssuer.generateBase32Secret();
        when(userRepository.findById(userId)).thenReturn(Optional.of(enrolledAdmin(secret)));
        long step = Instant.now().getEpochSecond() / 30;

        String approval = issuer.issueAfterVerification(userId, StepUpTokenIssuer.generateTotp(secret, step), "TOTP",
                "FORCE_BURN_EWG26", "POST /api/v1/x/1", null);
        String ordinary = issuer.issueAfterVerification(userId, StepUpTokenIssuer.generateTotp(secret, step + 1), "TOTP");

        assertThat(payloadOf(approval)).contains("\"use\":\"dual_control\"").contains("registerwerk-dual-control");
        assertThat(payloadOf(ordinary)).doesNotContain("dual_control").doesNotContain("stepup_scope")
                .doesNotContain("\"jti\"");
    }

    @Test
    @DisplayName("C2: the canonical body is part of every approval by default - changing one field changes the bound target")
    void approvalToken_bindsBodyForAnyReason() {
        String secret = StepUpTokenIssuer.generateBase32Secret();
        when(userRepository.findById(userId)).thenReturn(Optional.of(enrolledAdmin(secret)));
        long step = Instant.now().getEpochSecond() / 30;
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();

        String a = issuer.issueAfterVerification(userId, StepUpTokenIssuer.generateTotp(secret, step), "TOTP",
                "KYC_APPROVE", "POST /api/v1/entities/1/kyc/approve", mapper.readTree("{\"overrideNote\":\"accepted\"}"));
        String b = issuer.issueAfterVerification(userId, StepUpTokenIssuer.generateTotp(secret, step + 1), "TOTP",
                "KYC_APPROVE", "POST /api/v1/entities/1/kyc/approve", mapper.readTree("{\"overrideNote\":\"other\"}"));

        String targetA = payloadOf(a).replaceAll(".*\"stepup_target\":\"([^\"]+)\".*", "$1");
        String targetB = payloadOf(b).replaceAll(".*\"stepup_target\":\"([^\"]+)\".*", "$1");
        assertThat(targetA).isNotEqualTo(targetB);
    }

    @Test
    @DisplayName("enroll refuses when TOTP is already enrolled")
    void enroll_rejectsWhenAlreadyEnrolled() {
        AppUser user = freshUser();
        user.setTotpSecret("EXISTINGSECRET");
        user.setTotpEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> issuer.enroll(userId, "pw"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("already enrolled");
    }

    @Test
    @DisplayName("confirmEnrollment activates TOTP given a correct code for the enrolled secret")
    void confirmEnrollment_activatesOnValidCode() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        StepUpTokenIssuer.EnrollmentStart start = issuer.enroll(userId, "pw");

        long currentStep = Instant.now().getEpochSecond() / 30;
        String validCode = StepUpTokenIssuer.generateTotp(start.secret(), currentStep);

        issuer.confirmEnrollment(userId, validCode);

        assertThat(user.isTotpEnabled()).isTrue();
        assertThat(user.getTotpEnrolledAt()).isNotNull();
    }

    @Test
    @DisplayName("confirmEnrollment rejects an incorrect code and leaves TOTP inactive")
    void confirmEnrollment_rejectsInvalidCode() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        issuer.enroll(userId, "pw");

        assertThatThrownBy(() -> issuer.confirmEnrollment(userId, "000000"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(user.isTotpEnabled()).isFalse();
    }

    @Test
    @DisplayName("confirmEnrollment refuses when enroll was never called")
    void confirmEnrollment_rejectsWithoutPriorEnroll() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> issuer.confirmEnrollment(userId, "123456"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("No pending TOTP enrolment");
    }

    @Test
    @DisplayName("confirmEnrollment refuses when TOTP is already active")
    void confirmEnrollment_rejectsWhenAlreadyEnabled() {
        AppUser user = freshUser();
        user.setTotpSecret("EXISTINGSECRET");
        user.setTotpEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> issuer.confirmEnrollment(userId, "123456"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("already enrolled");
    }

    @Test
    @DisplayName("generated secrets are random Base32 and decode cleanly back to 20 bytes")
    void generatedSecret_isValidBase32() {
        String secret = StepUpTokenIssuer.generateBase32Secret();

        assertThat(secret).matches("[A-Z2-7]+");
        assertThat(StepUpTokenIssuer.decodeBase32(secret)).hasSize(20);
    }

    @Test
    @DisplayName("Wave 5b: enrolment holds no connection while it reserves attempts and runs BCrypt; only the write is transactional")
    void enrolmentOpensItsTransactionOnlyForTheWrite() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        java.util.List<String> violations = new java.util.ArrayList<>();
        when(state.reserveAttempt(any())).thenAnswer(inv -> {
            if (txOpen.get()) violations.add("reserveAttempt inside a transaction");
            return true;
        });
        when(passwordEncoder.matches("pw", "hash")).thenAnswer(inv -> {
            if (txOpen.get()) violations.add("BCrypt inside a transaction");
            return true;
        });
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> {
            if (!txOpen.get()) violations.add("save outside a transaction");
            return inv.getArgument(0);
        });
        org.mockito.Mockito.doAnswer(inv -> {
            if (!txOpen.get()) violations.add("audit event published outside a transaction (would be dropped)");
            return null;
        }).when(events).publishEvent(any(Object.class));

        issuer.enroll(userId, "pw");

        assertThat(violations).isEmpty();
    }
}
