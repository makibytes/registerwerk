package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import de.makibytes.registerwerk.stepup.internal.StepUpTokenIssuer;
import de.makibytes.registerwerk.stepup.web.dto.StepUpRequest;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
import de.makibytes.registerwerk.stepup.web.dto.TotpEnrollmentConfirmRequest;
import de.makibytes.registerwerk.stepup.web.dto.TotpEnrollmentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the previously-missing TOTP enrolment flow :
 * without it, a real production deployment had no way to ever obtain a step-up token, making
 * every {@code @RequiresStepUp} endpoint permanently unreachable. Deliberately does NOT rely
 * on {@code registerwerk.auth.step-up.allow-unenrolled} (used by other step-up ITs to skip
 * TOTP) — this test exercises the real enrol → confirm → step-up loop with a genuine code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("TOTP enrolment integration test — step-up is reachable end-to-end")
class StepUpEnrollmentIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static final String ADMIN_EMAIL = "enroll-admin@test.local";
    static final String ADMIN_PASSWORD = "Sup3rSecret!";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    String bearerToken;

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.entra-enabled", () -> "false");
        registry.add("registerwerk.auth.default-admin.email", () -> ADMIN_EMAIL);
        registry.add("registerwerk.auth.default-admin.password", () -> ADMIN_PASSWORD);
        // Override the test profile's default (true) — this test specifically verifies the
        // real production behavior (no enrolment => step-up refused), which allow-unenrolled
        // would otherwise bypass entirely.
        registry.add("registerwerk.auth.step-up.allow-unenrolled", () -> "false");
    }

    @BeforeEach
    void login() {
        ResponseEntity<LoginResponse> login = rest.postForEntity(
                "/api/v1/public/auth/login",
                new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
                LoginResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Set-Cookie header carries the bearer token now, not the body — see LoginResponse.
        bearerToken = AuthApiIT.extractSessionToken(login);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @DisplayName("enroll -> confirm -> step-up succeeds with a real TOTP code, and a stale/unenrolled attempt is rejected first")
    void fullEnrollmentAndStepUpLoop() {
        // Before enrolment, step-up with any code must fail — proving the endpoint isn't
        // silently permissive prior to enrolment.
        ResponseEntity<String> beforeEnroll = rest.postForEntity(
                "/api/v1/auth/step-up",
                new HttpEntity<>(new StepUpRequest("123456", "TOTP", null), authHeaders()),
                String.class);
        assertThat(beforeEnroll.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<TotpEnrollmentResponse> enrollResponse = rest.postForEntity(
                "/api/v1/auth/step-up/enroll",
                new HttpEntity<>(null, authHeaders()),
                TotpEnrollmentResponse.class);
        assertThat(enrollResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secret = enrollResponse.getBody().secret();
        assertThat(secret).isNotBlank();
        assertThat(enrollResponse.getBody().otpauthUri()).contains("otpauth://totp/Registerwerk");

        long currentStep = Instant.now().getEpochSecond() / 30;
        String code = StepUpTokenIssuer.generateTotp(secret, currentStep);

        ResponseEntity<Void> confirmResponse = rest.postForEntity(
                "/api/v1/auth/step-up/enroll/confirm",
                new HttpEntity<>(new TotpEnrollmentConfirmRequest(code), authHeaders()),
                Void.class);
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // A second enroll attempt must now be refused — already active.
        ResponseEntity<String> secondEnroll = rest.postForEntity(
                "/api/v1/auth/step-up/enroll",
                new HttpEntity<>(null, authHeaders()),
                String.class);
        assertThat(secondEnroll.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A fresh code from the now-enrolled secret must successfully mint a step-up token.
        long stepUpStep = Instant.now().getEpochSecond() / 30;
        String stepUpCode = StepUpTokenIssuer.generateTotp(secret, stepUpStep);
        ResponseEntity<StepUpResponse> stepUpResponse = rest.postForEntity(
                "/api/v1/auth/step-up",
                new HttpEntity<>(new StepUpRequest(stepUpCode, "TOTP", null), authHeaders()),
                StepUpResponse.class);
        assertThat(stepUpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stepUpResponse.getBody().stepUpToken()).isNotBlank();
    }
}
