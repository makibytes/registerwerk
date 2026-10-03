package de.makibytes.registerwerk.payment;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5A-11 end to end through the real filter chain, step-up aspect and PostgreSQL: creation and
 * attestation need a second approver, the creator cannot attest, an unattested EMT rail cannot
 * be enabled, and swapping the token address voids the attestation and switches the rail off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Payment rail attestation controls (5A-11)")
class PaymentRailAttestationIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";
    private static final String TOKEN_A = "0x" + "aa".repeat(20);
    private static final String TOKEN_B = "0x" + "bb".repeat(20);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.dev-secret", () -> SECRET);
        registry.add("registerwerk.payment.onchain-verify", () -> "false");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository appUsers;
    @LocalServerPort int port;

    private UUID operator() {
        AppUser u = new AppUser();
        u.setEmail("op-" + UUID.randomUUID() + "@test.local");
        return appUsers.save(u).getId();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String body, UUID actor, String reason,
                                        UUID approver) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(TestJwt.mint(SECRET, actor, true, null, null, "REGISTRY_ADMIN"));
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        if (approver != null) {
            h.set("X-Dual-Control-Token", body == null || body.isBlank()
                    ? TestJwt.dualControl(SECRET, approver, reason, method.name(), "/api/v1/payment-rails" + path, "REGISTRY_ADMIN")
                    : TestJwt.dualControlWithBody(SECRET, approver, reason, method.name(),
                            "/api/v1/payment-rails" + path, body, "REGISTRY_ADMIN"));
        }
        return rest.exchange("http://localhost:" + port + "/api/v1/payment-rails" + path, method,
                new HttpEntity<>(body, h), String.class);
    }

    private String railJson(String code, String issuer, UUID chain, String token) {
        return "{\"code\":\"" + code + "\",\"displayName\":\"Rail\",\"railType\":\"STABLECOIN\",\"currency\":\"EUR\","
                + "\"decimals\":6,\"issuerName\":\"" + issuer + "\",\"issuerLei\":\"LEI\",\"micarAuthorization\":\"BaFin\","
                + "\"emtFlag\":true,\"redemptionAtPar\":true,"
                + "\"chainAddresses\":[{\"chainConfigId\":\"" + chain + "\",\"tokenAddress\":\"" + token + "\"}]}";
    }

    private String railId(String code) {
        return jdbc.queryForObject("SELECT id::text FROM payment_rail WHERE code = ?", String.class, code);
    }

    @Test
    @DisplayName("create/attest need a second approver; creator cannot attest; unattested EMT cannot be enabled; a token swap voids the attestation and disables the rail")
    void fullLifecycle() {
        UUID chain = UUID.randomUUID();
        jdbc.update("INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url) "
                + "VALUES (?, ?, 'T', 'EVM', 'TESTNET', 'http://localhost:1')", chain, "it-" + chain);
        UUID creator = operator();
        UUID approver = operator();
        UUID attester = operator();

        // creation without a second approver is refused (before: single step-up sufficed)
        assertThat(call(HttpMethod.POST, "", railJson("it-emt", "Issuer", chain, TOKEN_A), creator, null, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_rail WHERE code='it-emt'", Integer.class)).isZero();

        ResponseEntity<String> created = call(HttpMethod.POST, "", railJson("it-emt", "Issuer", chain, TOKEN_A),
                creator, "Payment rail creation", approver);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT enabled FROM payment_rail WHERE code='it-emt'", Boolean.class)).isFalse();
        String id = railId("it-emt");

        // the creator cannot attest their own entry, even with an independent approver
        assertThat(call(HttpMethod.POST, "/" + id + "/verify-micar", null, creator,
                "Payment rail MiCAR attestation", approver).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // unattested EMT cannot be enabled
        assertThat(call(HttpMethod.POST, "/" + id + "/enable", null, attester, "Payment rail enablement", approver)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // a third operator attests (with approver) and the rail can then be enabled
        ResponseEntity<String> attested = call(HttpMethod.POST, "/" + id + "/verify-micar", null, attester,
                "Payment rail MiCAR attestation", approver);
        assertThat(attested.getStatusCode()).as(attested.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(attested.getBody()).contains("\"micarVerified\":true");
        assertThat(call(HttpMethod.POST, "/" + id + "/enable", null, attester, "Payment rail enablement", approver)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT enabled FROM payment_rail WHERE code='it-emt'", Boolean.class)).isTrue();

        // swapping the token address voids the attestation and switches the EMT rail off with a reason
        ResponseEntity<String> updated = call(HttpMethod.PUT, "/" + id, railJson("it-emt", "Issuer", chain, TOKEN_B),
                creator, "Payment rail update", approver);
        assertThat(updated.getStatusCode()).as(updated.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).contains("\"micarVerified\":false")
                .contains("\"disabledReason\":\"MICAR_ATTESTATION_INVALIDATED\"").contains("\"enabled\":false");
        assertThat(jdbc.queryForObject("SELECT micar_attested_fingerprint FROM payment_rail WHERE code='it-emt'",
                String.class)).isNull();
        assertThat(call(HttpMethod.POST, "/" + id + "/enable", null, attester, "Payment rail enablement", approver)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
