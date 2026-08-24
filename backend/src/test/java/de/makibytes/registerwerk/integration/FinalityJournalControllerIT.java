package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import de.makibytes.registerwerk.stepup.web.dto.StepUpRequest;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code FinalityJournalController} — the operator "unresolved
 * compensation" queue's role gate, step-up enforcement, and error mapping. Mirrors
 * {@link TokenAdminControllerIT}'s pattern (real login + real step-up token against a
 * Testcontainers Postgres) rather than mocking security, since what's actually being verified here
 * is that the annotations on the controller are wired correctly end-to-end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("FinalityJournalController integration tests — role/step-up gates and error mapping")
class FinalityJournalControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static final String ADMIN_EMAIL = "finality-admin@test.local";
    static final String ADMIN_PASSWORD = "Sup3rSecret!";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    String bearerToken;
    String stepUpToken;

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.entra-enabled", () -> "false");
        registry.add("registerwerk.auth.default-admin.email", () -> ADMIN_EMAIL);
        registry.add("registerwerk.auth.default-admin.password", () -> ADMIN_PASSWORD);
    }

    @BeforeEach
    void authenticate() {
        ResponseEntity<LoginResponse> login = rest.postForEntity(
                "/api/v1/public/auth/login",
                new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
                LoginResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        bearerToken = AuthApiIT.extractSessionToken(login);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearerToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StepUpResponse> stepUp = rest.postForEntity(
                "/api/v1/auth/step-up",
                new HttpEntity<>(new StepUpRequest("123456", "TOTP", null), h),
                StepUpResponse.class);
        assertThat(stepUp.getStatusCode()).isEqualTo(HttpStatus.OK);
        stepUpToken = stepUp.getBody().stepUpToken();
    }

    @Test
    @DisplayName("an authenticated REGISTRY_ADMIN can list unresolved compensations")
    void listUnresolved_authenticated_returnsOk() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearerToken);

        ResponseEntity<List> response = rest.exchange(
                "/api/v1/finality-journal/unresolved", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(h), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("retry without a step-up token → 403, even for a REGISTRY_ADMIN")
    void retry_withoutStepUp_returns403() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearerToken); // regular token, no acr=stepup
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.postForEntity(
                "/api/v1/finality-journal/" + UUID.randomUUID() + "/retry",
                new HttpEntity<>(h), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("retry with a step-up token but an unknown chainEffectId passes the auth gate and 404s")
    void retry_withStepUpToken_unknownId_returns404() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(stepUpToken);
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.postForEntity(
                "/api/v1/finality-journal/" + UUID.randomUUID() + "/retry",
                new HttpEntity<>(h), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("acknowledge without a step-up token → 403")
    void acknowledge_withoutStepUp_returns403() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearerToken);
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.postForEntity(
                "/api/v1/finality-journal/" + UUID.randomUUID() + "/acknowledge",
                new HttpEntity<>(Map.of("reason", "reviewed, proceeding"), h), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("acknowledge with a step-up token but a blank reason fails validation with 400")
    void acknowledge_withStepUpToken_blankReason_returns400() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(stepUpToken);
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.postForEntity(
                "/api/v1/finality-journal/" + UUID.randomUUID() + "/acknowledge",
                new HttpEntity<>(Map.of("reason", ""), h), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("acknowledge with a step-up token, a valid reason, and an unknown chainEffectId "
            + "passes the auth+validation gates and 404s")
    void acknowledge_withStepUpToken_unknownId_returns404() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(stepUpToken);
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.postForEntity(
                "/api/v1/finality-journal/" + UUID.randomUUID() + "/acknowledge",
                new HttpEntity<>(Map.of("reason", "reviewed, proceeding"), h), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
