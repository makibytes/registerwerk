package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.KycDocument;
import de.makibytes.registerwerk.kyc.api.KycDocumentRepository;
import de.makibytes.registerwerk.kyc.web.dto.BeneficialOwnerRequest;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8-03: a COMPLIANCE_OFFICER takes the KYC / EDD / beneficial-owner decisions in the operator portal, so
 * the portal needs a read that carries exactly what a decision needs. The full entity read, the wallet and
 * balance endpoints stay REGISTRY_ADMIN (+AUDIT) only; the scoped read is a separate endpoint with an
 * allow-listed field set, so a later column on the entity can never leak into the officer's view.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("T8-03 KYC queue and scoped review for the compliance officer")
class KycReviewIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    @SuppressWarnings("unused")
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.dev-secret", () -> SECRET);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private LegalEntityRepository entities;
    @Autowired private KycDocumentRepository documents;
    @LocalServerPort private int port;

    private static final Set<String> REVIEW_FIELDS = Set.of(
            "entityId", "entityNumber", "legalName", "entityType", "entityStatus", "registrationCountry",
            "registrationNumber", "leiCode", "incorporationDate", "homeJurisdiction", "kycStatus", "kycExpiryDate",
            "documents", "checklist", "jurisdictionApprovals", "ownership", "beneficialOwners", "screening",
            "gaps", "decisions");

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders as(String role) {
        return as(role, null);
    }

    private HttpHeaders as(String role, UUID entityId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(TestJwt.mint(SECRET, ACTOR, false, null, entityId, role));
        return h;
    }

    private UUID createEntity(String name) {
        EntityCreateRequest req = new EntityCreateRequest(EntityType.ISSUER, name, null, "DE", null, null);
        ResponseEntity<EntityResponse> r = rest.exchange(url("/api/v1/entities"), HttpMethod.POST,
                new HttpEntity<>(req, as("REGISTRY_ADMIN")), EntityResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().id();
    }

    private void setKyc(UUID id, KycStatus status, LocalDate expiry) {
        LegalEntity e = entities.findById(id).orElseThrow();
        e.setKycStatus(status);
        e.setKycExpiryDate(expiry);
        entities.save(e);
    }

    private void addDocument(UUID id) {
        KycDocument doc = new KycDocument();
        doc.setLegalEntityId(id);
        doc.setDocumentType(KycDocument.DocumentType.COMMERCIAL_REGISTER_EXTRACT);
        doc.setMimeType("application/pdf");
        doc.setFileName("register.pdf");
        doc.setStorageRef("inline");
        doc.setSizeBytes(10L);
        doc.setContentHash("00".repeat(32));
        doc.setExpiresAt(LocalDate.now().plusYears(1));
        documents.save(doc);
    }

    private void addOwner(UUID id) {
        BeneficialOwnerRequest body = new BeneficialOwnerRequest(
                new BeneficialOwnerRequest.NaturalPersonInput("Erika", "Mustermann", LocalDate.of(1980, 1, 1), "DE", "DE",
                        null, null, "Secret Street 1", null, "Berlin", "10115", "DE"),
                new BigDecimal("80"), BeneficialOwner.ControlType.DIRECT_OWNERSHIP, "register", null);
        ResponseEntity<String> r = rest.exchange(url("/api/v1/entities/" + id + "/beneficial-owners"), HttpMethod.POST,
                new HttpEntity<>(body, as("COMPLIANCE_OFFICER")), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<Map<String, Object>> getMap(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    private HttpStatus status(String path, HttpHeaders headers) {
        return HttpStatus.valueOf(rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode().value());
    }

    @Test
    @DisplayName("the officer reads the scoped review: exactly the allow-listed fields, no admin data")
    void officerReadsScopedReview() {
        UUID id = createEntity("Review Scope GmbH");
        setKyc(id, KycStatus.IN_PROGRESS, null);
        addDocument(id);
        addOwner(id);

        ResponseEntity<Map<String, Object>> r = getMap("/api/v1/kyc/entities/" + id + "/review", as("COMPLIANCE_OFFICER"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = r.getBody();
        assertThat(body.keySet()).isEqualTo(REVIEW_FIELDS);
        assertThat(body).containsEntry("legalName", "Review Scope GmbH").containsEntry("kycStatus", "IN_PROGRESS");
        assertThat((List<?>) body.get("documents")).hasSize(1);
        assertThat((List<?>) body.get("beneficialOwners")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> screening = (Map<String, Object>) body.get("screening");
        assertThat(screening.keySet()).containsExactlyInAnyOrder(
                "entityHitUnresolved", "beneficialOwnerHitUnresolved", "relyingOnStaleResult");
        // beneficial-owner personal data beyond what the decision needs stays out
        assertThat(r.toString()).doesNotContain("Secret Street").doesNotContain("idp");
    }

    @Test
    @DisplayName("the officer still gets 403 on the full entity read, the entity list and the wallet/balance endpoints")
    void officerStillCannotReadAdminData() {
        UUID id = createEntity("Review Admin Data GmbH");
        HttpHeaders co = as("COMPLIANCE_OFFICER");

        assertThat(status("/api/v1/entities/" + id, co)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/api/v1/entities", co)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/api/v1/admin/wallets", co)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/api/v1/admin/wallets/" + UUID.randomUUID() + "/balances", co)).isEqualTo(HttpStatus.FORBIDDEN);
        // the document endpoints stay open to the officer exactly as K6 decided
        assertThat(status("/api/v1/entities/" + id + "/kyc/documents", co)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("REGISTRY_ADMIN reads the review and keeps the full entity read")
    void adminUnchanged() {
        UUID id = createEntity("Review Admin GmbH");
        setKyc(id, KycStatus.IN_PROGRESS, null);

        assertThat(status("/api/v1/kyc/entities/" + id + "/review", as("REGISTRY_ADMIN"))).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/kyc/queue", as("REGISTRY_ADMIN"))).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/entities/" + id, as("REGISTRY_ADMIN"))).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("every other role gets 403 on the queue and the review, no token gets 401, unknown entity 404")
    void otherRolesRefused() {
        UUID id = createEntity("Review Refused GmbH");
        for (String role : List.of("AUDIT", "TRADER", "COMPANY_ADMIN", "RELATIONSHIP_MANAGER", "SUPPORT_AGENT")) {
            HttpHeaders h = as(role, id);
            assertThat(status("/api/v1/kyc/queue", h)).as(role + " queue").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status("/api/v1/kyc/entities/" + id + "/review", h)).as(role + " review").isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(status("/api/v1/kyc/queue", new HttpHeaders())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status("/api/v1/kyc/entities/" + UUID.randomUUID() + "/review", as("COMPLIANCE_OFFICER")))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the queue lists entities awaiting a decision, expiring KYC and evidence gaps, with reasons")
    void queueListsWorkWithReasons() {
        UUID pending = createEntity("Queue Pending GmbH");
        setKyc(pending, KycStatus.IN_PROGRESS, null);
        UUID expiring = createEntity("Queue Expiring GmbH");
        setKyc(expiring, KycStatus.APPROVED, LocalDate.now().plusDays(10));
        UUID notStarted = createEntity("Queue Not Started GmbH");
        addOwner(pending);

        List<Map<String, Object>> queue = getList("/api/v1/kyc/queue", as("COMPLIANCE_OFFICER")).getBody();

        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        queue.forEach(i -> byId.put((String) i.get("entityId"), i));
        assertThat(byId).containsKeys(pending.toString(), expiring.toString()).doesNotContainKey(notStarted.toString());
        assertThat(reasons(byId.get(pending.toString()))).contains("KYC_IN_PROGRESS", "BO_UNVERIFIED");
        assertThat(reasons(byId.get(expiring.toString()))).contains("KYC_EXPIRING", "NO_BENEFICIAL_OWNER");
        assertThat(byId.get(pending.toString()).keySet()).containsExactlyInAnyOrder(
                "entityId", "entityName", "homeJurisdiction", "kycStatus", "kycExpiryDate", "reasons");
    }

    @SuppressWarnings("unchecked")
    private static List<String> reasons(Map<String, Object> item) {
        return (List<String>) item.get("reasons");
    }
}
