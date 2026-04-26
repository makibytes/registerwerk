package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.web.dto.LoginRequest;
import de.makibytes.registerwerk.web.dto.LoginResponse;
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
    @DisplayName("Valid admin credentials return a JWT")
    void validLogin_returnsToken() {
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            url("/api/v1/public/auth/login"),
            new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD),
            LoginResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
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
        String token = loginResponse.getBody().token();

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
