package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.domain.enums.EntityType;
import de.makibytes.registerwerk.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.web.dto.EntityResponse;
import de.makibytes.registerwerk.web.dto.EntityUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("LegalEntity API integration tests")
class LegalEntityApiIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private EntityCreateRequest buildCreateRequest(String name) {
        return new EntityCreateRequest(
            EntityType.ISSUER,
            name,
            "CHE-123.456.789",
            "CH",
            null,
            null
        );
    }

    private ResponseEntity<EntityResponse> createEntity(String name) {
        return restTemplate.postForEntity(
            url("/api/v1/entities"),
            buildCreateRequest(name),
            EntityResponse.class
        );
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "REGISTRY_ADMIN")
    @DisplayName("POST /api/v1/entities should return 201 and the created entity")
    void createEntity_shouldReturn201AndEntity() {
        ResponseEntity<EntityResponse> response = createEntity("Helvetica Fintech AG");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        EntityResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.currentName()).isEqualTo("Helvetica Fintech AG");
        assertThat(body.id()).isNotNull();
        assertThat(body.entityNumber()).startsWith("ENT-");
    }

    @Test
    @WithMockUser(roles = "REGISTRY_ADMIN")
    @DisplayName("GET /api/v1/entities/{id} should return 404 when entity does not exist")
    void getEntity_shouldReturn404WhenNotFound() {
        UUID randomId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.getForEntity(
            url("/api/v1/entities/{id}"), String.class, randomId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @WithMockUser(roles = "REGISTRY_ADMIN")
    @DisplayName("GET /api/v1/entities should return paginated results")
    void listEntities_shouldReturnPaginatedResults() {
        createEntity("Entity Alpha GmbH");
        createEntity("Entity Beta AG");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            url("/api/v1/entities?size=10&page=0"),
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("content");
        assertThat(body).containsKey("totalElements");
    }

    @Test
    @WithMockUser(roles = "REGISTRY_ADMIN")
    @DisplayName("PATCH /api/v1/entities/{id} should return 200 with updated fields")
    void updateEntity_shouldReturn200WithUpdatedFields() {
        ResponseEntity<EntityResponse> created = createEntity("Original Name GmbH");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = created.getBody().id();

        EntityUpdateRequest updateRequest = new EntityUpdateRequest(
            "Updated Name AG", "213800XXXXXXXXXXXXXX", null, "DE", null);

        ResponseEntity<EntityResponse> updated = restTemplate.exchange(
            url("/api/v1/entities/{id}"),
            HttpMethod.PATCH,
            new HttpEntity<>(updateRequest),
            EntityResponse.class,
            id
        );

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().currentName()).isEqualTo("Updated Name AG");
        assertThat(updated.getBody().leiCode()).isEqualTo("213800XXXXXXXXXXXXXX");
    }

    @Test
    @WithMockUser(roles = "REGISTRY_ADMIN")
    @DisplayName("POST /api/v1/entities/{id}/suspend should return 200 and entity with SUSPENDED status")
    void suspendEntity_shouldReturn200AndChangedStatus() {
        ResponseEntity<EntityResponse> created = createEntity("Suspendable Corp AG");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = created.getBody().id();

        ResponseEntity<Void> suspendResponse = restTemplate.exchange(
            url("/api/v1/entities/{id}/suspend"),
            HttpMethod.POST,
            null,
            Void.class,
            id
        );

        assertThat(suspendResponse.getStatusCode()).isIn(HttpStatus.NO_CONTENT, HttpStatus.OK);

        ResponseEntity<EntityResponse> getResponse = restTemplate.getForEntity(
            url("/api/v1/entities/{id}"), EntityResponse.class, id);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().status().name()).isEqualTo("SUSPENDED");
    }
}
