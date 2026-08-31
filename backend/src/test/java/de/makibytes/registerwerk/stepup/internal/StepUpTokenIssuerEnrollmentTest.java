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
import org.springframework.security.access.AccessDeniedException;

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

    private StepUpTokenIssuer issuer;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        props.setDevSecret("test-secret-at-least-32-bytes-long!!");
        issuer = new StepUpTokenIssuer(userRepository, new JwtMintingService(props), false);
        lenient().when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static AppUser freshUser() {
        AppUser user = new AppUser();
        user.setEmail("admin@test.local");
        return user;
    }

    @Test
    @DisplayName("enroll generates and stores a secret, but does not activate TOTP yet")
    void enroll_generatesSecretWithoutActivating() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        StepUpTokenIssuer.EnrollmentStart start = issuer.enroll(userId);

        assertThat(start.secret()).isNotBlank();
        assertThat(start.otpauthUri()).contains(start.secret()).contains("otpauth://totp/Registerwerk");
        assertThat(user.getTotpSecret()).isEqualTo(start.secret());
        assertThat(user.isTotpEnabled()).isFalse();
    }

    @Test
    @DisplayName("enroll refuses when TOTP is already enrolled")
    void enroll_rejectsWhenAlreadyEnrolled() {
        AppUser user = freshUser();
        user.setTotpSecret("EXISTINGSECRET");
        user.setTotpEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> issuer.enroll(userId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("already enrolled");
    }

    @Test
    @DisplayName("confirmEnrollment activates TOTP given a correct code for the enrolled secret")
    void confirmEnrollment_activatesOnValidCode() {
        AppUser user = freshUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        StepUpTokenIssuer.EnrollmentStart start = issuer.enroll(userId);

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
        issuer.enroll(userId);

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
}
