package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.auth.api.JwtMintingService;

import de.makibytes.registerwerk.audit.internal.AuditEvent;
import de.makibytes.registerwerk.audit.internal.AuditEventRepository;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import de.makibytes.registerwerk.kyc.web.dto.KycJurisdictionApprovalResponse;
import de.makibytes.registerwerk.screening.internal.ScreeningRun;
import de.makibytes.registerwerk.screening.internal.ScreeningRunRepository;
import de.makibytes.registerwerk.screening.internal.ScreeningStatus;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
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
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

    @Autowired
    private ScreeningRunRepository screeningRunRepository;

    @Autowired
    private AppUserRepository appUserRepository;

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
        UUID entityId = response.getBody().id();
        awaitInitialScreening(entityId);
        return entityId;
    }

    /**
     * Entity onboarding publishes screening asynchronously.  Wait for that run before seeding
     * any follow-up result so a late ERROR cannot nondeterministically become the latest result
     * after the fixture's CLEAR row.
     */
    private void awaitInitialScreening(UUID entityId) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            boolean completed = screeningRunRepository.findByEntityIdOrderByStartedAtDesc(entityId).stream()
                .anyMatch(run -> "OPEN_SANCTIONS".equals(run.getProvider())
                    && run.getStatus() != ScreeningStatus.PENDING);
            if (completed) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for initial screening", e);
            }
        }
        throw new AssertionError("Initial screening did not complete for entity " + entityId);
    }

    private HttpHeaders authHeaders(String... roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(signedJwt(roles));
        return headers;
    }

    private String signedJwt(String... roles) {
        return signedJwt("00000000-0000-0000-0000-000000000001", false, roles);
    }

    /**
     * The jurisdiction approve/reject endpoints carry {@code @RequiresStepUp(requireSecondApprover
     * = true)} — the aspect runs before the controller body, so exercising the actual approve/
     * reject/override logic (not just the step-up gate) requires a full step-up token for the
     * caller AND a dual-control token from a second, currently-enabled REGISTRY_ADMIN
     * {@code AppUser} (the validator re-checks the DB, not just the JWT claims).
     */
    private HttpHeaders stepUpHeaders(String... roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(signedJwt("00000000-0000-0000-0000-000000000001", true, roles));
        // Every call site in this file hits the same jurisdiction-approve endpoint
        // (@RequiresStepUp(reason = "KYC_JURISDICTION_APPROVE")) — the dual-control token
        // must carry a matching stepup_scope claim .
        headers.set("X-Dual-Control-Token", dualControlToken("KYC_JURISDICTION_APPROVE"));
        return headers;
    }

    private String dualControlToken(String scope) {
        AppUser approver = new AppUser();
        approver.setEmail("approver-" + UUID.randomUUID() + "@test.local");
        UUID approverId = appUserRepository.save(approver).getId();
        return signedJwtWithScope(approverId.toString(), scope, "REGISTRY_ADMIN");
    }

    private String signedJwt(String sub, boolean stepUp, String... roles) {
        return signedJwt(sub, stepUp, null, roles);
    }

    /** @param scope embedded as the {@code stepup_scope} claim when non-null . */
    private String signedJwtWithScope(String sub, String scope, String... roles) {
        return signedJwt(sub, true, scope, roles);
    }

    private String signedJwt(String sub, boolean stepUp, String scope, String... roles) {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            long iat = Instant.now().getEpochSecond();
            long exp = iat + 3600;
            String rolesJson = String.join(",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList());
            String payload = base64Url("{"
                // The HS256 decoder is pinned to this issuer, so knowing the signing secret is
                // not on its own enough to mint an accepted token.
                + "\"iss\":\"" + JwtMintingService.LOCAL_ISSUER + "\","
                + "\"sub\":\"" + sub + "\","
                + "\"roles\":[" + rolesJson + "],"
                + (stepUp ? "\"acr\":\"stepup\"," : "")
                + (scope != null ? "\"stepup_scope\":\"" + scope + "\"," : "")
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

    /**
     * Inserts a completed CLEAR sanctions screening run for the entity.
     * The screening gate fails closed (GwG §10): without a clear run,
     * jurisdiction approval is blocked even for REGISTRY_ADMIN.
     */
    private void recordClearScreening(UUID entityId) {
        ScreeningRun run = new ScreeningRun();
        run.setEntityId(entityId);
        run.setTriggerType(ScreeningTrigger.ENTITY_ONBOARDING);
        // The gate evaluates the latest result of every configured provider.  A clearance
        // under an arbitrary fixture-only name must not mask an OPEN_SANCTIONS failure.
        run.setProvider("OPEN_SANCTIONS");
        run.setStatus(ScreeningStatus.CLEAR);
        run.setStartedAt(Instant.now());
        run.setCompletedAt(Instant.now());
        screeningRunRepository.save(run);
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
    @DisplayName("Jurisdiction approval is blocked when no successful sanctions screening exists (fail closed)")
    void approvalBlockedWithoutScreening() {
        UUID entityId = createEntityAndGetId("Unscreened Entity GmbH");
        addMandatoryDeEwpgDocs(entityId);

        ResponseEntity<String> response = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("expiresAt", LocalDate.now().plusYears(1).toString()),
                stepUpHeaders("COMPLIANCE_OFFICER")
            ),
            String.class,
            entityId
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("sanctions");
    }

    @Test
    @DisplayName("Compliance officer can approve jurisdiction when implemented checklist controls pass")
    void complianceOfficerCanApproveWhenCompliant() {
        UUID entityId = createEntityAndGetId("Compliance Officer Happy Path GmbH");
        addMandatoryDeEwpgDocs(entityId);
        recordClearScreening(entityId);

        ResponseEntity<KycJurisdictionApprovalResponse> response = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("expiresAt", LocalDate.now().plusYears(2).toString()),
                stepUpHeaders("COMPLIANCE_OFFICER")
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
                stepUpHeaders("COMPLIANCE_OFFICER")
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
        recordClearScreening(entityId);

        ResponseEntity<KycJurisdictionApprovalResponse> response = restTemplate.exchange(
            url("/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("overrideNote", "Approved after enhanced manual review"),
                stepUpHeaders("REGISTRY_ADMIN")
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
