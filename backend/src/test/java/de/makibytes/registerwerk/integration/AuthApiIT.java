package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
import de.makibytes.registerwerk.admin.web.dto.ImpersonateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Auth API integration tests")
class AuthApiIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    private static final String ADMIN_EMAIL = "admin@test.local";
    private static final String ADMIN_PASSWORD = "Sup3rSecret!";

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Use HS256 dev decoder (not a real OIDC issuer)
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.entra-enabled", () -> "false");
        registry.add("registerwerk.auth.default-admin.email", () -> ADMIN_EMAIL);
        registry.add("registerwerk.auth.default-admin.password", () -> ADMIN_PASSWORD);
        registry.add("registerwerk.cors.allowed-origins", () ->
            "http://nibbler.local:4200,http://nibbler.local:4201");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private LegalEntityRepository legalEntityRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @LocalServerPort
    private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("Valid admin credentials set an httpOnly session cookie and return profile claims")
    void validLogin_returnsToken() {
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // No token in the body — it's the rw_session cookie now (SessionCookieService), so no
        // script running on the page (including an XSS payload) can read it.
        assertThat(extractSessionToken(response)).isNotBlank();
        assertThat(response.getBody().roles()).containsExactly("REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("Valid login from a configured remote frontend origin is allowed")
    void validLogin_fromConfiguredRemoteOrigin_returnsOk() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://nibbler.local:4200");

        ResponseEntity<LoginResponse> response = restTemplate.exchange(
            url("/api/v1/public/auth/login"),
            HttpMethod.POST,
            new HttpEntity<>(new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD), headers),
            LoginResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://nibbler.local:4200");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    @Test
    @DisplayName("Fresh login clears an orphaned admin restore cookie")
    void freshLogin_clearsOrphanedAdminRestoreCookie() {
        ResponseEntity<LoginResponse> firstLogin = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "rw_admin_session=" + extractSessionToken(firstLogin));
        ResponseEntity<LoginResponse> secondLogin = restTemplate.exchange(
            url("/api/v1/public/auth/login"),
            HttpMethod.POST,
            new HttpEntity<>(new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD), headers),
            LoginResponse.class
        );

        assertThat(secondLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setCookies(secondLogin)).anyMatch(cookie ->
            cookie.startsWith("rw_admin_session=;") && cookie.contains("Max-Age=0"));
    }

    @Test
    @DisplayName("Asset responses serialize target-market categories after the service transaction closes")
    void assetResponses_serializeTargetMarketCategories() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity issuer = new LegalEntity();
        issuer.setEntityNumber("ISS-" + suffix);
        issuer.setType(EntityType.ISSUER);
        issuer.setStatus(EntityStatus.ACTIVE);
        issuer.setCurrentName("Target Market Test Issuer");
        issuer = legalEntityRepository.saveAndFlush(issuer);

        Asset asset = new Asset();
        asset.setAssetNumber("AST-" + suffix);
        asset.setIssuerId(issuer.getId());
        asset.setName("Target Market Test Asset");
        asset.setTokenStandard(TokenStandard.ERC20);
        asset.setTargetMarketCategories(Set.of(ClientCategory.PROFESSIONAL));
        assetRepository.saveAndFlush(asset);

        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(extractSessionToken(loginResponse));
        ResponseEntity<String> response = restTemplate.exchange(
            url("/api/v1/assets?issuerId=" + issuer.getId()),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"targetMarketCategories\":[\"PROFESSIONAL\"]");

        ResponseEntity<String> singleResponse = restTemplate.exchange(
            url("/api/v1/assets/" + asset.getId()),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );
        assertThat(singleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(singleResponse.getBody()).contains("\"targetMarketCategories\":[\"PROFESSIONAL\"]");
    }

    @Test
    @DisplayName("Impersonation stashes and restores the admin session without restoring an impersonation token")
    void impersonationSession_restoresOnlyTheOriginalAdminSession() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity target = new LegalEntity();
        target.setEntityNumber("ISS-" + suffix);
        target.setType(EntityType.ISSUER);
        target.setStatus(EntityStatus.ACTIVE);
        target.setCurrentName("Impersonation Test Issuer");
        target = legalEntityRepository.saveAndFlush(target);

        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );
        String adminToken = extractSessionToken(loginResponse);

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        ResponseEntity<ImpersonateResponse> mintResponse = restTemplate.exchange(
            url("/api/v1/impersonation"),
            HttpMethod.POST,
            new HttpEntity<>(java.util.Map.of("entityId", target.getId()), adminHeaders),
            ImpersonateResponse.class
        );
        assertThat(mintResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mintResponse.getBody()).isNotNull();

        HttpHeaders exchangeHeaders = new HttpHeaders();
        exchangeHeaders.add(HttpHeaders.COOKIE, "rw_session=" + adminToken);
        ResponseEntity<LoginResponse> exchangeResponse = restTemplate.exchange(
            url("/api/v1/public/auth/impersonate"),
            HttpMethod.POST,
            new HttpEntity<>(java.util.Map.of("token", mintResponse.getBody().token()), exchangeHeaders),
            LoginResponse.class
        );
        String impersonationToken = extractSessionToken(exchangeResponse);
        assertThat(extractCookieValue(exchangeResponse, "rw_admin_session")).isEqualTo(adminToken);
        assertThat(exchangeResponse.getBody()).isNotNull();
        assertThat(exchangeResponse.getBody().impersonating()).isTrue();

        // An impersonation token is never a restorable admin session. This models a stale or
        // nested handoff cookie and must fail closed rather than leave the user impersonating.
        HttpHeaders invalidExitHeaders = new HttpHeaders();
        invalidExitHeaders.setBearerAuth(impersonationToken);
        invalidExitHeaders.add(HttpHeaders.COOKIE,
            "rw_session=" + impersonationToken + "; rw_admin_session=" + impersonationToken);
        ResponseEntity<Void> invalidExit = restTemplate.exchange(
            url("/api/v1/auth/exit-impersonation"),
            HttpMethod.POST,
            new HttpEntity<>(invalidExitHeaders),
            Void.class
        );
        assertThat(invalidExit.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setCookies(invalidExit)).anyMatch(cookie -> cookie.startsWith("rw_session=;") && cookie.contains("Max-Age=0"));
        assertThat(setCookies(invalidExit)).anyMatch(cookie -> cookie.startsWith("rw_admin_session=;") && cookie.contains("Max-Age=0"));

        HttpHeaders exitHeaders = new HttpHeaders();
        exitHeaders.setBearerAuth(impersonationToken);
        exitHeaders.add(HttpHeaders.COOKIE,
            "rw_session=" + impersonationToken + "; rw_admin_session=" + adminToken);
        ResponseEntity<Void> exitResponse = restTemplate.exchange(
            url("/api/v1/auth/exit-impersonation"),
            HttpMethod.POST,
            new HttpEntity<>(exitHeaders),
            Void.class
        );
        assertThat(exitResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(extractSessionToken(exitResponse)).isEqualTo(adminToken);

        HttpHeaders restoredHeaders = new HttpHeaders();
        restoredHeaders.setBearerAuth(adminToken);
        ResponseEntity<LoginResponse> restoredSession = restTemplate.exchange(
            url("/api/v1/auth/session"),
            HttpMethod.GET,
            new HttpEntity<>(restoredHeaders),
            LoginResponse.class
        );
        assertThat(restoredSession.getBody()).isNotNull();
        assertThat(restoredSession.getBody().impersonating()).isFalse();
        assertThat(restoredSession.getBody().roles()).contains("REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("Exit impersonation does not log out a session that is not impersonating")
    void exitImpersonation_fromRegularAdmin_keepsSession() {
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );
        String adminToken = extractSessionToken(loginResponse);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.add(HttpHeaders.COOKIE, "rw_session=" + adminToken);
        ResponseEntity<Void> response = restTemplate.exchange(
            url("/api/v1/auth/exit-impersonation"),
            HttpMethod.POST,
            new HttpEntity<>(headers),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setCookies(response)).noneMatch(cookie -> cookie.startsWith("rw_session="));
        assertThat(setCookies(response)).anyMatch(cookie -> cookie.startsWith("rw_admin_session=;") && cookie.contains("Max-Age=0"));
    }

    @Test
    @DisplayName("Impersonation refuses a non-active target entity")
    void impersonation_nonActiveEntity_isForbidden() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity suspended = new LegalEntity();
        suspended.setEntityNumber("ISS-" + suffix);
        suspended.setType(EntityType.ISSUER);
        suspended.setStatus(EntityStatus.SUSPENDED);
        suspended.setCurrentName("Suspended Test Issuer");
        suspended = legalEntityRepository.saveAndFlush(suspended);

        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(extractSessionToken(loginResponse));
        ResponseEntity<String> response = restTemplate.exchange(
            url("/api/v1/impersonation"),
            HttpMethod.POST,
            new HttpEntity<>(java.util.Map.of("entityId", suspended.getId()), headers),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("A disabled admin's already-issued token cannot start impersonation")
    void impersonation_disabledAdminIsForbidden() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity target = new LegalEntity();
        target.setEntityNumber("ISS-" + suffix);
        target.setType(EntityType.ISSUER);
        target.setStatus(EntityStatus.ACTIVE);
        target.setCurrentName("Active Test Issuer");
        target = legalEntityRepository.saveAndFlush(target);

        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );
        assertThat(loginResponse.getBody()).isNotNull();
        AppUser admin = appUserRepository.findById(UUID.fromString(loginResponse.getBody().userId()))
            .orElseThrow();
        admin.setEnabled(false);
        appUserRepository.saveAndFlush(admin);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(extractSessionToken(loginResponse));
            ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/impersonation"),
                HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("entityId", target.getId()), headers),
                String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            // The seeded administrator is shared by this integration-test class; always restore
            // it so method ordering cannot turn this into a source of unrelated failures.
            admin.setEnabled(true);
            appUserRepository.saveAndFlush(admin);
        }
    }

    @Test
    @DisplayName("Minted token authorizes a protected API call")
    void mintedToken_authorizesProtectedEndpoint() {
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = extractSessionToken(loginResponse);

        // Sent as an explicit Authorization header rather than replaying the Set-Cookie value —
        // proves the resource server's normal header-based bearer auth still works standalone
        // (SessionCookieAuthorizationHeaderFilter only kicks in when no such header is present).
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> apiResponse = restTemplate.exchange(
            url("/api/v1/entities"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );

        assertThat(apiResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Session cookie alone (no Authorization header) authorizes a protected API call")
    void sessionCookie_authorizesProtectedEndpoint() {
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );
        String token = extractSessionToken(loginResponse);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "rw_session=" + token);
        ResponseEntity<String> apiResponse = restTemplate.exchange(
            url("/api/v1/entities"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );

        assertThat(apiResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Cookie authentication rotates CSRF token and keeps browser mutations usable")
    void sessionCookie_rotatesCsrfTokenForNextMutation() {
        ResponseEntity<String> bootstrapResponse = restTemplate.getForEntity(
            url("/api/v1/public/auth/config"),
            String.class
        );
        String bootstrapCsrfToken = extractCookieValue(bootstrapResponse, "XSRF-TOKEN");

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + bootstrapCsrfToken);
        loginHeaders.set("X-XSRF-TOKEN", bootstrapCsrfToken);
        ResponseEntity<LoginResponse> loginResponse = restTemplate.exchange(
            url("/api/v1/public/auth/login"),
            HttpMethod.POST,
            new HttpEntity<>(new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD), loginHeaders),
            LoginResponse.class
        );
        String sessionToken = extractSessionToken(loginResponse);

        HttpHeaders readHeaders = new HttpHeaders();
        readHeaders.add(HttpHeaders.COOKIE,
            "rw_session=" + sessionToken + "; XSRF-TOKEN=" + bootstrapCsrfToken);
        ResponseEntity<String> readResponse = restTemplate.exchange(
            url("/api/v1/entities"),
            HttpMethod.GET,
            new HttpEntity<>(readHeaders),
            String.class
        );

        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> csrfCookies = setCookies(readResponse).stream()
            .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
            .toList();
        assertThat(csrfCookies)
            .as("the rotated token must be written after Spring expires the old token")
            .isNotEmpty();
        String rotatedCsrfToken = csrfCookies.stream()
            .map(cookie -> cookie.substring("XSRF-TOKEN=".length()).split(";", 2)[0])
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No replacement CSRF token in response: " + csrfCookies));
        assertThat(rotatedCsrfToken).isNotEqualTo(bootstrapCsrfToken);

        HttpHeaders mutationHeaders = new HttpHeaders();
        mutationHeaders.add(HttpHeaders.COOKIE,
            "rw_session=" + sessionToken + "; XSRF-TOKEN=" + rotatedCsrfToken);
        mutationHeaders.set("X-XSRF-TOKEN", rotatedCsrfToken);
        ResponseEntity<Void> mutationResponse = restTemplate.exchange(
            url("/api/v1/auth/exit-impersonation"),
            HttpMethod.POST,
            new HttpEntity<>(mutationHeaders),
            Void.class
        );

        assertThat(mutationResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("Session cookie alone does not exempt a mutating request from CSRF validation")
    void sessionCookie_withoutCsrfToken_isRejectedOnMutatingRequest() {
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );
        String token = extractSessionToken(loginResponse);

        // No X-XSRF-TOKEN header, and no Authorization header — if the bearer-token-carrying
        // request were (incorrectly) resolved from this cookie, oauth2ResourceServer()'s
        // automatic CSRF exemption would let this straight through. It must not.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "rw_session=" + token);
        ResponseEntity<String> apiResponse = restTemplate.exchange(
            url("/api/v1/auth/exit-impersonation"),
            HttpMethod.POST,
            new HttpEntity<>(headers),
            String.class
        );

        // CsrfFilter rejects with 403, but Tomcat's /error re-dispatch re-enters the (unrelated)
        // security chain as an anonymous request and the resource server's authentication entry
        // point wins the race for the final status the client sees, downgrading it to 401 — a
        // pre-existing quirk of how AccessDeniedHandlerImpl's sendError interacts with Spring
        // Boot's default error handling, orthogonal to what this test asserts. Either status
        // proves the request was rejected outright, which is what matters here.
        assertThat(apiResponse.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
    }

    /** Set-Cookie header carries the bearer token now — see LoginResponse's Javadoc. */
    static String extractSessionToken(ResponseEntity<?> response) {
        return extractCookieValue(response, "rw_session");
    }

    static String extractCookieValue(ResponseEntity<?> response, String name) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("Set-Cookie header on login response").isNotNull();
        return cookies.stream()
            .filter(c -> c.startsWith(name + "="))
            .map(c -> c.substring((name + "=").length()).split(";", 2)[0])
            .findFirst()
            .orElseThrow(() -> new AssertionError("No " + name + " cookie in response: " + cookies));
    }

    static List<String> setCookies(ResponseEntity<?> response) {
        return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
    }

    @Test
    @DisplayName("Wrong password returns 401")
    void wrongPassword_returns401() {
        ResponseEntity<String> response = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, "wrong-password"),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
