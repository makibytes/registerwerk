package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.domain.audit.AuditEvent;
import de.makibytes.registerwerk.domain.entity.KycDocument;
import de.makibytes.registerwerk.domain.enums.EntityType;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AuditEventRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.KycDocumentRepository;
import de.makibytes.registerwerk.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.web.dto.EntityResponse;
import de.makibytes.registerwerk.web.dto.KycJurisdictionApprovalResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@ActiveProfiles("test")
@DisplayName("KYC jurisdiction approval integration tests")
class KycJurisdictionApprovalIT {

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

    @Autowired
    private KycDocumentRepository kycDocumentRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @LocalServerPort
    private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private UUID createEntityAndGetId(String name) {
        EntityCreateRequest req = new EntityCreateRequest(
            EntityType.ISSUER, name, null, "DE", null, null);
        ResponseEntity<EntityResponse> response = restTemplate.exchange(
            url("/api/v1/entities"),
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders("REGISTRY_ADMIN")),
            EntityResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private HttpHeaders authHeaders(String... roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(signedJwt(roles));
        return headers;
    }

    private String signedJwt(String... roles) {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            long iat = Instant.now().getEpochSecond();
            long exp = iat + 3600;
            String rolesJson = String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList());
            String payload = base64Url("{"
                + "\"sub\":\"00000000-0000-0000-0000-000000000001\","
                + "\"roles\":[" + rolesJson + "],"
                + "\"iat\":" + iat + ","
                + "\"exp\":" + exp
                + "}");
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            Key key = new SecretKeySpec(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] sig = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private void createDocument(UUID entityId, KycDocument.DocumentType type) {
        KycDocument doc = new KycDocument();
        doc.setLegalEntityId(entityId);
        doc.setDocumentType(type);
        doc.setMimeType("application/pdf");
        doc.setFileName(type.name().toLowerCase() + ".pdf");
        doc.setStorageRef("inline");
        doc.setSizeBytes(1234L);
        doc.setContentHash(sha256(doc.getFileName()));
        doc.setExpiresAt(LocalDate.now().plusYears(1));
        kycDocumentRepository.save(doc);
    }

    private void addMandatoryDeEwpgDocs(UUID entityId) {
        createDocument(entityId, KycDocument.DocumentType.CERTIFICATE_OF_INCORPORATION);
        createDocument(entityId, KycDocument.DocumentType.COMMERCIAL_REGISTER_EXTRACT);
        createDocument(entityId, KycDocument.DocumentType.UBO_DECLARATION);
        createDocument(entityId, KycDocument.DocumentType.BENEFICIAL_OWNER_REGISTER_EXTRACT);
        createDocument(entityId, KycDocument.DocumentType.OWNERSHIP_STRUCTURE_CHART);
        createDocument(entityId, KycDocument.DocumentType.IDENTITY_DOCUMENT);
        createDocument(entityId, KycDocument.DocumentType.BOARD_RESOLUTION);
        createDocument(entityId, KycDocument.DocumentType.AML_QUESTIONNAIRE);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Compliance officer can approve jurisdiction when checklist is fully compliant")
    void complianceOfficerCanApproveWhenCompliant() {
        UUID entityId = createEntityAndGetId("Compliance Officer Happy Path GmbH");
        addMandatoryDeEwpgDocs(entityId);

        ResponseEntity<KycJurisdictionApprovalResponse> response = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("expiresAt", LocalDate.now().plusYears(2).toString()),
                authHeaders("COMPLIANCE_OFFICER")
            ),
            KycJurisdictionApprovalResponse.class,
            entityId
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("APPROVED");
        assertThat(response.getBody().overrideNote()).isNull();
    }

    @Test
    @DisplayName("Compliance officer cannot approve non-compliant jurisdiction even with override note")
    void complianceOfficerCannotOverrideNonCompliant() {
        UUID entityId = createEntityAndGetId("Compliance Officer Override Blocked GmbH");

        ResponseEntity<String> response = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("overrideNote", "Attempted override without admin role"),
                authHeaders("COMPLIANCE_OFFICER")
            ),
            String.class,
            entityId
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Admin can override non-compliant jurisdiction approval with explicit note")
    void adminCanOverrideNonCompliant() {
        UUID entityId = createEntityAndGetId("Admin Override Path GmbH");

        ResponseEntity<KycJurisdictionApprovalResponse> response = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("overrideNote", "Approved after enhanced manual review"),
                authHeaders("REGISTRY_ADMIN")
            ),
            KycJurisdictionApprovalResponse.class,
            entityId
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("APPROVED");
        assertThat(response.getBody().overrideNote()).isEqualTo("Approved after enhanced manual review");
    }

    @Test
    @DisplayName("Audit report endpoint lists KYC override approvals")
    void overrideReportListsApprovals() {
        UUID entityId = createEntityAndGetId("Override Report Entity GmbH");

        // Create one override approval event.
        AuditEvent event = new AuditEvent();
        event.setEventType("KYC_JURISDICTION_APPROVED");
        event.setSubjectType("LegalEntity");
        event.setSubjectId(entityId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("jurisdiction", "DE_EWPG");
        payload.put("overrideApplied", true);
        payload.put("overrideNote", "Audit report seed override");
        payload.put("missingCount", 2);
        payload.put("expiredCount", 0);
        payload.put("tooOldCount", 0);
        event.setPayload(payload);
        auditEventRepository.save(event);

        ResponseEntity<String> response = restTemplate.exchange(
            url("/api/v1/audit/reports/kyc-overrides?jurisdiction=DE_EWPG"),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders("AUDIT")),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"totalElements\"");
    }
}
