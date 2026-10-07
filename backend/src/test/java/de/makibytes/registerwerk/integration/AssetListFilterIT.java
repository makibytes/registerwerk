package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
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
 * {@code GET /api/v1/assets} must honour every filter the portals send: {@code status},
 * {@code tokenStandard}, {@code issuerId} and the free-text {@code search} (the operator asset
 * list sends all four; before this fix {@code search} and {@code tokenStandard} were silently
 * ignored and the unfiltered page came back).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Asset list filter integration tests")
class AssetListFilterIT {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-32-bytes!!";
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    private AssetRepository assetRepository;

    @LocalServerPort
    private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders headers(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt);
        return headers;
    }

    private HttpHeaders adminHeaders() {
        return headers(TestJwt.mint(TEST_JWT_SECRET, ADMIN_ID, false, null, null, "REGISTRY_ADMIN"));
    }

    private UUID createIssuer(String name) {
        ResponseEntity<EntityResponse> response = restTemplate.exchange(url("/api/v1/entities"), HttpMethod.POST,
            new HttpEntity<>(new EntityCreateRequest(EntityType.ISSUER, name, "CHE-123.456.789", "CH", null, null),
                adminHeaders()),
            EntityResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private Asset saveAsset(UUID issuerId, String name, String isin, TokenStandard standard, AssetStatus status) {
        Asset asset = new Asset();
        asset.setAssetNumber("TST-" + UUID.randomUUID().toString().substring(0, 12));
        asset.setIssuerId(issuerId);
        asset.setName(name);
        asset.setIsin(isin);
        asset.setTokenStandard(standard);
        asset.setStatus(status);
        return assetRepository.save(asset);
    }

    private ResponseEntity<Map<String, Object>> get(String query, HttpHeaders headers) {
        return restTemplate.exchange(url("/api/v1/assets?" + query), HttpMethod.GET, new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {});
    }

    private Map<String, Object> list(String query) {
        ResponseEntity<Map<String, Object>> response = get(query, adminHeaders());
        assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream().map(a -> (String) a.get("name")).toList();
    }

    @Test
    @DisplayName("GET /api/v1/assets honours search and tokenStandard (plus status/issuerId) and combines them")
    void listAssets_filtersBySearchAndTokenStandard() {
        String tag = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        String isinTail = tag.substring(2, 10).toUpperCase();
        UUID issuer = createIssuer(tag + " Issuer AG");
        saveAsset(issuer, tag + " Green Bond", "XS" + isinTail + "01", TokenStandard.ERC3643, AssetStatus.ISSUED);
        saveAsset(issuer, tag + " Money Fund", "XS" + isinTail + "02", TokenStandard.ERC4626, AssetStatus.DRAFT);
        saveAsset(issuer, tag + " Plain Token", null, TokenStandard.ERC20, AssetStatus.DRAFT);

        // search: case-insensitive contains on the name
        assertThat(names(list("search=" + tag.toLowerCase() + "&size=50")))
            .containsExactlyInAnyOrder(tag + " Green Bond", tag + " Money Fund", tag + " Plain Token");
        assertThat(names(list("search= " + tag.toUpperCase() + " money &size=50")))
            .as("search is trimmed").containsExactly(tag + " Money Fund");
        // search: ISIN (case-insensitive contains)
        assertThat(names(list("search=xs" + isinTail.toLowerCase() + "02&size=50"))).containsExactly(tag + " Money Fund");

        // tokenStandard narrows to that standard only
        assertThat(names(list("tokenStandard=ERC3643&issuerId=" + issuer + "&size=50")))
            .containsExactly(tag + " Green Bond");
        assertThat(((Number) list("tokenStandard=ERC20&issuerId=" + issuer + "&size=1").get("totalElements")).longValue())
            .isEqualTo(1L);

        // all filters AND-combine
        assertThat(names(list("search=" + tag + "&tokenStandard=ERC4626&status=DRAFT&size=50")))
            .containsExactly(tag + " Money Fund");
        assertThat(names(list("search=" + tag + "&tokenStandard=ERC4626&status=ISSUED&size=50"))).isEmpty();
        assertThat(names(list("search=" + tag + "&status=DRAFT&size=50")))
            .containsExactlyInAnyOrder(tag + " Money Fund", tag + " Plain Token");

        // paging and sort still apply to the filtered set
        Map<String, Object> firstPage = list("search=" + tag + "&size=2&page=0&sort=name,asc");
        assertThat(((Number) firstPage.get("totalElements")).longValue()).isEqualTo(3L);
        assertThat(names(firstPage)).containsExactly(tag + " Green Bond", tag + " Money Fund");

        // LIKE wildcards in the term are literals, not patterns
        assertThat(names(list("search=%&size=50"))).isEmpty();
        assertThat(names(list("search=" + tag + "_Green&size=50"))).isEmpty();
    }

    @Test
    @DisplayName("GET /api/v1/assets rejects an unknown tokenStandard and an oversized search term with 400")
    void listAssets_rejectsInvalidFilterValues() {
        for (String query : new String[] {"tokenStandard=NOPE", "search=" + "x".repeat(201)}) {
            assertThat(get(query, adminHeaders()).getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(get("search=" + "x".repeat(200), adminHeaders()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("A customer caller stays scoped to its own entity, whatever issuerId/search it sends")
    void listAssets_customerStaysScopedToOwnEntity() {
        String tag = "Zq" + UUID.randomUUID().toString().substring(0, 8);
        UUID mine = createIssuer(tag + " Mine AG");
        UUID other = createIssuer(tag + " Other AG");
        saveAsset(mine, tag + " Own Bond", null, TokenStandard.ERC3643, AssetStatus.DRAFT);
        saveAsset(other, tag + " Foreign Bond", null, TokenStandard.ERC3643, AssetStatus.DRAFT);

        HttpHeaders customer = headers(TestJwt.mint(TEST_JWT_SECRET, UUID.randomUUID(), false, null, mine, "ISSUER"));
        ResponseEntity<Map<String, Object>> response =
            get("search=" + tag + "&tokenStandard=ERC3643&issuerId=" + other + "&size=50", customer);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(names(response.getBody())).containsExactly(tag + " Own Bond");
    }
}
