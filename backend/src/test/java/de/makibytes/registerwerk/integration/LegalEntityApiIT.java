package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.auth.api.JwtMintingService;

import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import de.makibytes.registerwerk.customer.web.dto.EntityUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("LegalEntity API integration tests")
class LegalEntityApiIT {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-32-bytes!!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    @SuppressWarnings("unused")
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.dev-secret", () -> TEST_JWT_SECRET);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LegalEntityRepository legalEntityRepository;

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
        return restTemplate.exchange(
            url("/api/v1/entities"),
            HttpMethod.POST,
            new HttpEntity<>(buildCreateRequest(name), authHeaders()),
            EntityResponse.class);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(signedJwt());
        return headers;
    }

    private String signedJwt() {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            long iat = Instant.now().getEpochSecond();
            long exp = iat + 3600;
            String payload = base64Url("{"
                // The HS256 decoder is pinned to this issuer, so knowing the signing secret is
                // not on its own enough to mint an accepted token.
                + "\"iss\":\"" + JwtMintingService.LOCAL_ISSUER + "\","
                + "\"sub\":\"00000000-0000-0000-0000-000000000001\","
                + "\"roles\":[\"REGISTRY_ADMIN\"],"
                + "\"iat\":" + iat + ","
                + "\"exp\":" + exp
                + "}");
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
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
    @DisplayName("GET /api/v1/entities/{id} should return 404 when entity does not exist")
    void getEntity_shouldReturn404WhenNotFound() {
        UUID randomId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.exchange(
            url("/api/v1/entities/{id}"),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            String.class,
            randomId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/v1/entities should return paginated results")
    void listEntities_shouldReturnPaginatedResults() {
        createEntity("Entity Alpha GmbH");
        createEntity("Entity Beta AG");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            url("/api/v1/entities?size=10&page=0"),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("content");
        assertThat(body).containsKey("totalElements");
    }

    private Map<String, Object> list(String query) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            url("/api/v1/entities?" + query),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> names(Map<String, Object> page) {
        return ((java.util.List<Map<String, Object>>) page.get("content")).stream()
            .map(e -> (String) e.get("currentName")).toList();
    }

    @Test
    @DisplayName("GET /api/v1/entities honours the kycStatus and search filters the operator portal sends")
    void listEntities_filtersByKycStatusAndSearch() {
        String tag = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        UUID pending = createEntity(tag + " Pending GmbH").getBody().id();
        createEntity(tag + " Untouched AG");
        var pendingEntity = legalEntityRepository.findById(pending).orElseThrow();
        pendingEntity.setKycStatus(KycStatus.IN_PROGRESS);
        legalEntityRepository.save(pendingEntity);

        // kycStatus: only the IN_PROGRESS entity (the dashboard 'Pending KYC' tile reads totalElements)
        Map<String, Object> inProgress = list("kycStatus=IN_PROGRESS&size=50");
        assertThat(names(inProgress)).contains(tag + " Pending GmbH").doesNotContain(tag + " Untouched AG");
        assertThat(((Number) inProgress.get("totalElements")).longValue()).isEqualTo(names(inProgress).size());
        Map<String, Object> notStarted = list("kycStatus=NOT_STARTED&size=50");
        assertThat(names(notStarted)).contains(tag + " Untouched AG").doesNotContain(tag + " Pending GmbH");

        // search: case-insensitive contains on the legal name, combinable with the other filters
        assertThat(names(list("search=" + tag.toLowerCase() + "&size=50")))
            .containsExactlyInAnyOrder(tag + " Pending GmbH", tag + " Untouched AG");
        assertThat(names(list("search=" + tag + " pend&kycStatus=IN_PROGRESS&size=50")))
            .containsExactly(tag + " Pending GmbH");
        assertThat(names(list("search=" + tag + " pend&kycStatus=NOT_STARTED&size=50"))).isEmpty();

        // search: entity number (prefix, case-insensitive)
        String number = legalEntityRepository.findById(pending).orElseThrow().getEntityNumber();
        assertThat(names(list("search=" + number.toLowerCase() + "&size=50"))).contains(tag + " Pending GmbH");

        // LIKE wildcards in the search term are literals, not patterns
        assertThat(names(list("search=%25&size=50"))).isEmpty();
    }

    @Test
    @DisplayName("GET /api/v1/entities rejects an unknown kycStatus and an oversized search term with 400")
    void listEntities_rejectsInvalidFilterValues() {
        for (String query : new String[] {"kycStatus=NOPE", "search=" + "x".repeat(201)}) {
            ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/entities?" + query), HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("PATCH /api/v1/entities/{id} should return 200 with updated fields")
    void updateEntity_shouldReturn200WithUpdatedFields() {
        ResponseEntity<EntityResponse> created = createEntity("Original Name GmbH");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = created.getBody().id();

        EntityUpdateRequest updateRequest = new EntityUpdateRequest(
            "Updated Name AG", "213800XXXXXXXXXXXXXX", null, "DE", null);

        HttpHeaders stepUpHeaders = authHeaders();
        stepUpHeaders.setBearerAuth(de.makibytes.registerwerk.TestJwt.mint(TEST_JWT_SECRET,
            UUID.fromString("00000000-0000-0000-0000-000000000001"), true, null, null, "REGISTRY_ADMIN"));
        ResponseEntity<EntityResponse> updated = restTemplate.exchange(
            url("/api/v1/entities/{id}"),
            HttpMethod.PATCH,
            new HttpEntity<>(updateRequest, stepUpHeaders),
            EntityResponse.class,
            id
        );

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().currentName()).isEqualTo("Updated Name AG");
        assertThat(updated.getBody().leiCode()).isEqualTo("213800XXXXXXXXXXXXXX");
    }

    @Test
    @DisplayName("POST /api/v1/entities/{id}/suspend without step-up and second approver is refused and changes nothing")
    void suspendEntity_withoutStepUp_isRefused() {
        ResponseEntity<EntityResponse> created = createEntity("Suspendable Corp AG");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = created.getBody().id();

        // Lifecycle changes need step-up + a second approver (6-21): a plain admin token is refused.
        ResponseEntity<String> suspendResponse = restTemplate.exchange(
            url("/api/v1/entities/{id}/suspend"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "test"), authHeaders()),
            String.class,
            id
        );
        assertThat(suspendResponse.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);

        ResponseEntity<EntityResponse> getResponse = restTemplate.exchange(
            url("/api/v1/entities/{id}"),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            EntityResponse.class,
            id);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().status().name()).isEqualTo("PENDING_ONBOARDING");
    }
}
