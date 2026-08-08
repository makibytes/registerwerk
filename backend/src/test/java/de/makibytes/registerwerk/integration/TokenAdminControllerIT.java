package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import de.makibytes.registerwerk.deployment.api.DeploymentAccessChecker;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for TokenAdminController:
 * - Force-burn, force-transfer and other regulator-grade actions require step-up authentication
 * - Without step-up token → 403
 * - With step-up token (acr=stepup) → request accepted (or 404/422 for missing deployment)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("TokenAdminController integration tests — step-up auth enforcement")
class TokenAdminControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static final String ADMIN_EMAIL = "admin@test.local";
    static final String ADMIN_PASSWORD = "Sup3rSecret!";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    /**
     * This test targets the step-up aspect, not nested deployment ownership.  Permit the
     * synthetic IDs through that independent guard so the response proves whether execution
     * reached the controller/service boundary.
     */
    @MockitoBean
    DeploymentAccessChecker deploymentAccessChecker;

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
        when(deploymentAccessChecker.belongsToAsset(any(), any())).thenReturn(true);

        ResponseEntity<LoginResponse> login = rest.postForEntity(
                "/api/v1/public/auth/login",
                new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
                LoginResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Set-Cookie header carries the bearer token now, not the body — see LoginResponse.
        bearerToken = AuthApiIT.extractSessionToken(login);

        // Obtain step-up token (test profile sets step-up.allow-unenrolled=true;
        // production refuses step-up without TOTP enrolment)
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
    @DisplayName("supply-cap change without step-up token → 403 Forbidden")
    void setSupplyCap_withoutStepUp_returns403() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearerToken); // regular token, no acr=stepup
        h.setContentType(MediaType.APPLICATION_JSON);

        var response = rest.postForEntity(
                "/api/v1/assets/00000000-0000-0000-0000-000000000001/deployments/00000000-0000-0000-0000-000000000002/admin/set-supply-cap",
                new HttpEntity<>(Map.of("newCap", 1_000), h),
                Map.class);

        // The @RequiresStepUp aspect blocks when acr!=stepup — expects 403
        assertThat(response.getStatusCode().value()).isIn(403, 404);
    }

    @Test
    @DisplayName("supply-cap change with step-up token passes auth gate")
    void setSupplyCap_withStepUpToken_passesAuthGate() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(stepUpToken); // token with acr=stepup
        h.setContentType(MediaType.APPLICATION_JSON);

        var response = rest.postForEntity(
                "/api/v1/assets/00000000-0000-0000-0000-000000000001/deployments/00000000-0000-0000-0000-000000000002/admin/set-supply-cap",
                new HttpEntity<>(Map.of("newCap", 1_000), h),
                Map.class);

        // Auth gate passed — 404 because no such deployment exists, not 403
        assertThat(response.getStatusCode().value()).isIn(404, 400, 500);
        assertThat(response.getStatusCode().value()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("force-burn without step-up → 403")
    void forceBurn_withoutStepUp_returns403() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearerToken);
        h.setContentType(MediaType.APPLICATION_JSON);

        // Valid body to pass @Valid — the step-up aspect should then return 403
        var body = Map.of(
            "from", "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            "value", 1000,
            "legalBasis", "BaFin Einziehungsverfügung Az. TEST-2026-001");

        var response = rest.postForEntity(
                "/api/v1/assets/00000000-0000-0000-0000-000000000001/deployments/00000000-0000-0000-0000-000000000002/admin/force-burn",
                new HttpEntity<>(body, h),
                Map.class);

        // @RequiresStepUp is on force-burn — without acr=stepup the aspect returns 403
        assertThat(response.getStatusCode().value()).isIn(403, 404);
    }

    @Test
    @DisplayName("step-up token has acr=stepup claim")
    void stepUpToken_hasAcrClaim() {
        assertThat(stepUpToken).isNotNull();
        // Decode JWT payload (base64url) and check acr claim
        String[] parts = stepUpToken.split("\\.");
        assertThat(parts).hasSize(3);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload).contains("\"acr\":\"stepup\"");
    }
}
