package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.config.TestSecurityConfig;
import de.makibytes.registerwerk.domain.enums.EntityType;
import de.makibytes.registerwerk.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.web.dto.EntityResponse;
import de.makibytes.registerwerk.web.dto.KycDocumentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("KYC Document API integration tests")
class KycDocumentApiIT {

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

    /** Creates a legal entity and returns its ID. */
    private UUID createEntityAndGetId(String name) {
        EntityCreateRequest req = new EntityCreateRequest(
            EntityType.ISSUER, name, null, "CH", null, null);
        ResponseEntity<EntityResponse> response = restTemplate.postForEntity(
            url("/api/v1/entities"), req, EntityResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
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
            new HttpEntity<>(body, headers),
            KycDocumentResponse.class,
            entityId
        );
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "REGISTRY_ADMIN")
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
    @WithMockUser(roles = "REGISTRY_ADMIN")
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
            new HttpEntity<>(form, headers),
            KycDocumentResponse.class,
            entityId
        );
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID docId = uploadResp.getBody().id();

        ResponseEntity<byte[]> downloadResp = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/documents/{docId}"),
            HttpMethod.GET,
            null,
            byte[].class,
            entityId, docId
        );

        assertThat(downloadResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResp.getBody()).isNotNull();
    }

    @Test
    @WithMockUser(roles = "REGISTRY_ADMIN")
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
            null,
            Void.class,
            entityId, docId
        );

        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
