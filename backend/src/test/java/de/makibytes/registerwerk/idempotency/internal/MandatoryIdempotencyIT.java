package de.makibytes.registerwerk.idempotency.internal;

import de.makibytes.registerwerk.TestJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4B-7 through the real filter chain against PostgreSQL: an OPERATOR token (no entity_id claim) on a
 * mandatory-key endpoint (payment-rail creation is annotated) - missing key is a 400, the same key
 * twice creates ONE rail and replays the first response, and the key is scoped per user + request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Mandatory Idempotency-Key on money-moving endpoints (P4B-7)")
class MandatoryIdempotencyIT {

    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
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
    @Autowired de.makibytes.registerwerk.auth.api.AppUserRepository appUserRepository;
    @LocalServerPort int port;

    private ResponseEntity<String> createRail(UUID operator, String key, String code) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(TestJwt.mint(SECRET, operator, true, null, null, "REGISTRY_ADMIN"));
        if (key != null) {
            h.set("Idempotency-Key", key);
        }
        // Rail creation is a 4-eyes action (5A-11): a second, DB-backed REGISTRY_ADMIN approves.
        de.makibytes.registerwerk.auth.api.AppUser approver = new de.makibytes.registerwerk.auth.api.AppUser();
        approver.setEmail("approver-" + UUID.randomUUID() + "@test.local");
        UUID approverId = appUserRepository.save(approver).getId();
        h.set("X-Dual-Control-Token",
                TestJwt.dualControl(SECRET, approverId, "Payment rail creation", "POST", "/api/v1/payment-rails", "REGISTRY_ADMIN"));
        String body = "{\"code\":\"" + code + "\",\"displayName\":\"Rail\",\"railType\":\"OFFCHAIN_SEPA\",\"currency\":\"EUR\","
                + "\"emtFlag\":false,\"redemptionAtPar\":false}";
        return rest.exchange("http://localhost:" + port + "/api/v1/payment-rails", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private int railCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_rail WHERE code = ?", Integer.class, code);
    }

    @Test
    @DisplayName("an operator POST without Idempotency-Key is rejected 400 IDEMPOTENCY_KEY_REQUIRED and does nothing")
    void missingKey_is400() {
        ResponseEntity<String> response = createRail(OPERATOR, null, "no-key-rail");
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(railCount("no-key-rail")).isZero();
    }

    @Test
    @DisplayName("the same key twice from an operator (no entity_id) executes once and replays the first response")
    void sameKeyTwice_executesOnce() {
        UUID operator = OPERATOR;
        String key = "op-key-" + UUID.randomUUID();
        ResponseEntity<String> first = createRail(operator, key, "once-rail");
        ResponseEntity<String> second = createRail(operator, key, "once-rail");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(railCount("once-rail")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT scope FROM idempotency_record WHERE idempotency_key = ?", String.class, key)).isEqualTo("USER");
    }

    @Test
    @DisplayName("a state-dependent handler refusal (here 400 already-exists), then the state is fixed: the identical retry (same key) executes exactly once")
    void businessConflict_isNotReplayed_retryExecutes() {
        createRail(OPERATOR, "op-key-" + UUID.randomUUID(), "conflict-rail");
        String key = "op-key-" + UUID.randomUUID();

        ResponseEntity<String> conflict = createRail(OPERATOR, key, "conflict-rail");
        assertThat(conflict.getStatusCode().value()).as(conflict.getBody()).isEqualTo(400);

        jdbc.update("DELETE FROM payment_rail WHERE code = 'conflict-rail'");

        ResponseEntity<String> retry = createRail(OPERATOR, key, "conflict-rail");
        assertThat(retry.getStatusCode()).as(retry.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("X-Idempotent-Replay")).isNull();
        assertThat(railCount("conflict-rail")).isEqualTo(1);
        ResponseEntity<String> replay = createRail(OPERATOR, key, "conflict-rail");
        assertThat(replay.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
        assertThat(railCount("conflict-rail")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different request body is a 422, not a silent replay")
    void sameKeyDifferentBody_is422() {
        UUID operator = OPERATOR;
        String key = "op-key-" + UUID.randomUUID();
        createRail(operator, key, "diff-a-rail");
        assertThat(createRail(operator, key, "diff-b-rail").getStatusCode().value()).isEqualTo(422);
        assertThat(railCount("diff-b-rail")).isZero();
    }

    @Test
    @DisplayName("another operator using the same key value is not served the first operator's response")
    void keyIsScopedPerUser() {
        String key = "shared-key-" + UUID.randomUUID();
        createRail(UUID.randomUUID(), key, "scope-a-rail");
        ResponseEntity<String> other = createRail(UUID.randomUUID(), key, "scope-b-rail");
        assertThat(other.getHeaders().getFirst("X-Idempotent-Replay")).isNull();
        assertThat(railCount("scope-b-rail")).isEqualTo(1);
    }

    @Test
    @DisplayName("V24: the outbox refuses a second row for the same request idempotency key")
    void outboxKeyIsUnique() {
        UUID chain = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chain_config (id, identifier, display_name, chain_type, network_type, rpc_url, enabled)
                VALUES (?, ?, 'Idem IT Chain', 'EVM', 'TESTNET', 'http://localhost:8545', true)
                """, chain, "idem-it-" + chain);
        String ident = "USER:u:key-0001#0";
        insertOutbox(chain, 1, ident);
        assertThatThrownBy(() -> insertOutbox(chain, 2, ident)).isInstanceOf(DuplicateKeyException.class);
        insertOutbox(chain, 3, null);
        insertOutbox(chain, 4, null);
    }

    private void insertOutbox(UUID chain, int nonce, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO evm_signed_submission (id, chain_config_id, chain_id, sender_address, nonce, tx_hash,
                    signed_payload, status, chain_name, network, contract_address, method_name, attempt_count,
                    idempotency_key)
                VALUES (?, ?, 1, ?, ?, ?, '0x01', 'PREPARED', 'ETHEREUM', 'TESTNET', ?, 'mint', 0, ?)
                """, UUID.randomUUID(), chain, "0x" + "ab".repeat(20), nonce,
                "0x" + String.format("%064x", nonce + 1000L), "0x" + "cd".repeat(20), idempotencyKey);
    }
}
