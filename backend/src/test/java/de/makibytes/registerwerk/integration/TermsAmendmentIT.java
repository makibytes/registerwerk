package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.audit.internal.AuditEvent;
import de.makibytes.registerwerk.audit.internal.AuditEventRepository;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** T3-10: economic terms locked after approval; the only way through is a 4-eyes amendment. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Terms locking and amendment integration tests")
class TermsAmendmentIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.dev-secret", () -> SECRET);
    }

    @Autowired TestRestTemplate rest;
    @Autowired AssetRepository assetRepository;
    @Autowired LegalEntityRepository legalEntityRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired AuditEventRepository auditEventRepository;
    @LocalServerPort int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpHeaders json(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(bearer);
        return h;
    }

    private Asset approvedAsset() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LegalEntity issuer = new LegalEntity();
        issuer.setEntityNumber("ISS-" + suffix);
        issuer.setType(EntityType.ISSUER);
        issuer.setStatus(EntityStatus.ACTIVE);
        issuer.setCurrentName("Amendment Test Issuer");
        issuer = legalEntityRepository.saveAndFlush(issuer);
        Asset asset = new Asset();
        asset.setAssetNumber("AST-" + suffix);
        asset.setIssuerId(issuer.getId());
        asset.setName("Amendment Test Note");
        asset.setTokenStandard(TokenStandard.ERC20);
        asset.setIssueSize(new BigDecimal("1000000"));
        asset.setStatus(AssetStatus.APPROVED);
        return assetRepository.saveAndFlush(asset);
    }

    @Test
    void issuerCannotPatchIssueSizeAfterApproval() {
        Asset asset = approvedAsset();
        HttpHeaders issuer = json(TestJwt.mint(SECRET, UUID.randomUUID(), false, null, asset.getIssuerId(), "ISSUER"));

        ResponseEntity<String> refused = rest.exchange(url("/api/v1/assets/" + asset.getId()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("issueSize", 2000000), issuer), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("issueSize").contains("terms-amendments");
        assertThat(assetRepository.findById(asset.getId()).orElseThrow().getIssueSize())
                .isEqualByComparingTo("1000000");

        // Unchanged economic values (a full-form save) and non-economic fields still go through.
        ResponseEntity<String> renamed = rest.exchange(url("/api/v1/assets/" + asset.getId()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", "Renamed Note", "issueSize", 1000000), issuer), String.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assetRepository.findById(asset.getId()).orElseThrow().getName()).isEqualTo("Renamed Note");
    }

    @Test
    void amendmentRequiresSecondApproverAndAuditsBeforeAfter() throws Exception {
        Asset asset = approvedAsset();
        String path = "/api/v1/assets/" + asset.getId() + "/terms-amendments";
        Map<String, Object> body = Map.of("issueSize", 1500000, "legalReference", "SchVG §5 resolution 2026-09-01");

        HttpHeaders stepUpOnly = json(TestJwt.mint(SECRET, OPERATOR, true, null, null, "REGISTRY_ADMIN"));
        assertThat(rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, stepUpOnly), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(assetRepository.findById(asset.getId()).orElseThrow().getIssueSize()).isEqualByComparingTo("1000000");

        AppUser approver = new AppUser();
        approver.setEmail("approver-" + UUID.randomUUID() + "@test.local");
        UUID approverId = appUserRepository.save(approver).getId();
        HttpHeaders fourEyes = json(TestJwt.mint(SECRET, OPERATOR, true, null, null, "REGISTRY_ADMIN"));
        fourEyes.set("X-Dual-Control-Token",
                TestJwt.dualControlWithBody(SECRET, approverId, "TERMS_AMENDMENT", "POST", path,
                        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body), "REGISTRY_ADMIN"));

        ResponseEntity<String> amended = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, fourEyes), String.class);
        assertThat(amended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assetRepository.findById(asset.getId()).orElseThrow().getIssueSize()).isEqualByComparingTo("1500000");

        AuditEvent audit = awaitAudit(asset.getId());
        assertThat(audit.getActorId()).isEqualTo(OPERATOR);
        Map<String, Object> payload = audit.getPayload();
        assertThat(payload).containsEntry("legalReference", "SchVG §5 resolution 2026-09-01");
        assertThat(payload.get("before")).isEqualTo(Map.of("issueSize", "1000000"));
        assertThat(payload.get("after")).isEqualTo(Map.of("issueSize", "1500000"));
        assertThat(payload.toString()).contains(approverId.toString());
    }

    private AuditEvent awaitAudit(UUID assetId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Optional<AuditEvent> hit = auditEventRepository
                    .findBySubjectTypeAndSubjectId("Asset", assetId, PageRequest.of(0, 50)).stream()
                    .filter(e -> "ASSET_TERMS_AMENDED".equals(e.getEventType()))
                    .findFirst();
            if (hit.isPresent()) {
                return hit.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("ASSET_TERMS_AMENDED audit event not recorded");
    }
}
