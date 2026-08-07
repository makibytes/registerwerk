package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.auth.web.dto.LoginRequest;
import de.makibytes.registerwerk.auth.web.dto.LoginResponse;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Auth API integration tests")
class AuthApiIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

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
    }

    @Autowired
    private TestRestTemplate restTemplate;

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
        // CookieBearerTokenResolver checks the header first (step-up tokens rely on that same
        // priority), so this equally proves the resolver's header path still works standalone.
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

    /** Set-Cookie header carries the bearer token now — see LoginResponse's Javadoc. */
    static String extractSessionToken(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("Set-Cookie header on login response").isNotNull();
        return cookies.stream()
            .filter(c -> c.startsWith("rw_session="))
            .map(c -> c.substring("rw_session=".length()).split(";", 2)[0])
            .findFirst()
            .orElseThrow(() -> new AssertionError("No rw_session cookie in login response: " + cookies));
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
