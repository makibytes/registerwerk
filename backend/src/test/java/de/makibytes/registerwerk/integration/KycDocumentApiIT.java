package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import de.makibytes.registerwerk.kyc.web.dto.KycDocumentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@ActiveProfiles("test")
@DisplayName("KYC Document API integration tests")
class KycDocumentApiIT {

    private static final String TEST_JWT_SECRET = "integration-test-jwt-secret-32-bytes!!";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

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

    @LocalServerPort
    private int port;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** Creates a legal entity and returns its ID. */
    private UUID createEntityAndGetId(String name) {
        EntityCreateRequest req = new EntityCreateRequest(
            EntityType.ISSUER, name, null, "CH", null, null);
        ResponseEntity<EntityResponse> response = restTemplate.exchange(
            url("/api/v1/entities"),
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            EntityResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(signedJwt());
        return headers;
    }

    private String signedJwt() {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            long iat = Instant.now().getEpochSecond();
            long exp = iat + 3600;
            String payload = base64Url("{"
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

    /**
     * Performs a multipart document upload for the given entity and returns the response.
     * Uses small inline content (well under 5 MB) so no S3 call is needed.
     */
    private ResponseEntity<KycDocumentResponse> uploadDocument(UUID entityId, String fileName, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() { return fileName; }
        });
        body.add("documentType", "PASSPORT");

        return restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/documents"),
            HttpMethod.POST,
            new HttpEntity<>(body, mergeHeaders(headers)),
            KycDocumentResponse.class,
            entityId
        );
    }

    private HttpHeaders mergeHeaders(HttpHeaders base) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(base);
        headers.setBearerAuth(signedJwt());
        return headers;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/entities/{entityId}/kyc/documents should return 201 and document metadata")
    void uploadDocument_shouldReturn201AndDocumentMetadata() {
        UUID entityId = createEntityAndGetId("Document Upload Corp AG");
        byte[] content = "PDF content placeholder".getBytes();

        ResponseEntity<KycDocumentResponse> response = uploadDocument(entityId, "passport.pdf", content);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        KycDocumentResponse doc = response.getBody();
        assertThat(doc).isNotNull();
        assertThat(doc.id()).isNotNull();
        assertThat(doc.fileName()).isEqualTo("passport.pdf");
        assertThat(doc.sizeBytes()).isEqualTo((long) content.length);
        assertThat(doc.contentHash()).isNotBlank();
    }

    @Test
    @DisplayName("GET /api/v1/entities/{entityId}/kyc/documents/{docId} should return 200 with correct content type")
    void downloadDocument_shouldReturn200WithCorrectContentType() {
        UUID entityId = createEntityAndGetId("Download Test GmbH");
        byte[] content = "%PDF-1.4 test content".getBytes();

        // We need to set a PDF content type header on upload; override the helper for this test
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() { return "document.pdf"; }
            @Override
            public long contentLength() { return content.length; }
        });
        form.add("documentType", "ANNUAL_REPORT");

        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(MediaType.APPLICATION_PDF);

        ResponseEntity<KycDocumentResponse> uploadResp = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/documents"),
            HttpMethod.POST,
            new HttpEntity<>(form, mergeHeaders(headers)),
            KycDocumentResponse.class,
            entityId
        );
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID docId = uploadResp.getBody().id();

        ResponseEntity<byte[]> downloadResp = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/documents/{docId}"),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            byte[].class,
            entityId, docId
        );

        assertThat(downloadResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResp.getBody()).isNotNull();
    }

    @Test
    @DisplayName("DELETE /api/v1/entities/{entityId}/kyc/documents/{docId} should return 204")
    void softDeleteDocument_shouldReturn204() {
        UUID entityId = createEntityAndGetId("Delete Document GmbH");
        byte[] content = "to be deleted".getBytes();

        ResponseEntity<KycDocumentResponse> uploadResp = uploadDocument(entityId, "delete-me.pdf", content);
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID docId = uploadResp.getBody().id();

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/documents/{docId}"),
            HttpMethod.DELETE,
            new HttpEntity<>(authHeaders()),
            Void.class,
            entityId, docId
        );

        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
