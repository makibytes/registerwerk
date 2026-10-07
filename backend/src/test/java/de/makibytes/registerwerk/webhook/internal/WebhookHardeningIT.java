package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryOutcome;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryStatus;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V28 + the webhook API against a real database: SSRF refusal, encryption at rest, no status oracle, role gate, SKIP LOCKED claim, legacy-secret backfill. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Webhook hardening integration test (K4)")
class WebhookHardeningIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";

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
    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookSubscriptionRepository subscriptions;
    @Autowired WebhookDeliveryRepository deliveries;
    @Autowired WebhookStartupMaintenance maintenance;
    @Autowired WebhookSecretCipher cipher;
    @Autowired TransactionTemplate tx;
    @LocalServerPort int port;

    private final UUID entityId = UUID.randomUUID();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Object> req(Object body, boolean stepUp, String role) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(TestJwt.mint(SECRET, UUID.randomUUID(), stepUp, null, entityId, role));
        return new HttpEntity<>(body, h);
    }

    @Test
    void endToEnd() {
        // SSRF: cloud metadata / private / non-https targets are refused with 400
        for (String bad : List.of("http://169.254.169.254/latest/meta-data", "https://10.0.0.5/x",
                "https://[::ffff:127.0.0.1]/x", "https://100.64.0.1/x")) {
            assertThat(rest.exchange(url("/api/v1/me/webhooks"), HttpMethod.POST,
                    req(Map.of("url", bad), false, "COMPANY_ADMIN"), String.class).getStatusCode())
                    .as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        // management is COMPANY_ADMIN only
        assertThat(rest.exchange(url("/api/v1/me/webhooks"), HttpMethod.POST,
                req(Map.of("url", "https://93.184.216.34/hook"), false, "INVESTOR"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // create: secret returned once, stored encrypted
        ResponseEntity<Map> created = rest.exchange(url("/api/v1/me/webhooks"), HttpMethod.POST,
                req(Map.of("url", "https://93.184.216.34/hook"), false, "COMPANY_ADMIN"), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String plaintext = (String) created.getBody().get("secret");
        UUID id = UUID.fromString((String) created.getBody().get("id"));
        assertThat(plaintext).isNotBlank();
        String stored = jdbc.queryForObject("SELECT secret FROM webhook_subscription WHERE id = ?", String.class, id);
        assertThat(stored).startsWith("enc:v1:").doesNotContain(plaintext);
        assertThat(cipher.decrypt(stored, id)).isEqualTo(plaintext);

        // list never shows the secret
        ResponseEntity<String> list = rest.exchange(url("/api/v1/me/webhooks"), HttpMethod.GET,
                req(null, false, "COMPANY_ADMIN"), String.class);
        assertThat(list.getBody()).doesNotContain(plaintext).doesNotContain("\"secret\":\"");

        // rotation needs step-up, then keeps the previous secret
        assertThat(rest.exchange(url("/api/v1/me/webhooks/" + id + "/rotate-secret"), HttpMethod.POST,
                req(null, false, "COMPANY_ADMIN"), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> rotated = rest.exchange(url("/api/v1/me/webhooks/" + id + "/rotate-secret"), HttpMethod.POST,
                req(null, true, "COMPANY_ADMIN"), Map.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) rotated.getBody().get("secret")).isNotEqualTo(plaintext);
        assertThat(jdbc.queryForObject("SELECT secret_previous_enc FROM webhook_subscription WHERE id = ?", String.class, id))
                .isEqualTo(stored);

        // delivery list: coarse outcome, no HTTP code
        WebhookDelivery d = new WebhookDelivery();
        d.setSubscriptionId(id);
        d.setEventType(WebhookEventType.KYC_APPROVED);
        d.setPayload("{}");
        d.setStatus(WebhookDeliveryStatus.FAILED);
        d.setOutcome(WebhookDeliveryOutcome.RECEIVER_ERROR);
        d.setResponseCode(503);
        d.setNextAttemptAt(Instant.now().minusSeconds(5));
        deliveries.saveAndFlush(d);
        String json = rest.exchange(url("/api/v1/me/webhooks/" + id + "/deliveries"), HttpMethod.GET,
                req(null, false, "COMPANY_ADMIN"), String.class).getBody();
        assertThat(json).contains("\"outcome\":\"RECEIVER_ERROR\"").contains("\"eventId\"").contains("\"nextAttemptAt\"")
                .doesNotContain("responseCode").doesNotContainPattern("\\b503\\b"); // word-bounded: a microsecond like ".503562Z" is not the code

        // sweep claim: due rows are locked with SKIP LOCKED and leased
        List<UUID> claimed = tx.execute(s -> deliveries.claimDue(Instant.now(), 100).stream().map(WebhookDelivery::getId).toList());
        assertThat(claimed).contains(d.getId());
    }

    @Test
    @DisplayName("legacy plaintext secrets are encrypted by the maintenance run and remain usable")
    void legacyBackfill() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO webhook_subscription (id, entity_id, url, secret, event_types) VALUES (?,?,?,?,?)",
                id, entityId, "https://93.184.216.34/legacy", "legacy-plain-secret", "");
        UUID bad = UUID.randomUUID();
        jdbc.update("INSERT INTO webhook_subscription (id, entity_id, url, secret, event_types) VALUES (?,?,?,?,?)",
                bad, entityId, "http://169.254.169.254/x", "another-plain", "");

        maintenance.runOnce();

        String stored = jdbc.queryForObject("SELECT secret FROM webhook_subscription WHERE id = ?", String.class, id);
        assertThat(stored).startsWith("enc:v1:");
        assertThat(cipher.decrypt(stored, id)).isEqualTo("legacy-plain-secret");
        assertThat(jdbc.queryForObject("SELECT enabled FROM webhook_subscription WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT disabled_reason FROM webhook_subscription WHERE id = ?", String.class, bad))
                .isEqualTo("URL_POLICY");
        assertThat(jdbc.queryForObject("SELECT enabled FROM webhook_subscription WHERE id = ?", Boolean.class, bad)).isFalse();
        assertThat(subscriptions.findById(bad)).isPresent();
    }
}
