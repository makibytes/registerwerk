package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.TestJwt;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.stepup.web.dto.StepUpResponse;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8-02 against the real filter chain and a real PostgreSQL: the approval queue, and the approver token it mints
 * checked by the real endpoint's validator (TOTP reset: no body; KYC approve: body bound).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Approval queue (T8-02): request, approve, claim, use")
class ApprovalQueueIT {

    private static final String SECRET = "integration-test-jwt-secret-32-bytes!!";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.entra-enabled", () -> "false");
        registry.add("registerwerk.auth.dev-secret", () -> SECRET);
    }

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired TotpSecretStore secretStore;
    @Autowired ApprovalRequestService service;
    @LocalServerPort int port;

    // ── fixtures ─────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private AppUser user(String prefix, AppUserRole role) {
        AppUser u = new AppUser();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash(encoder.encode("Sup3rSecret!pw"));
        u.setRoles(Set.of(role));
        return users.save(u);
    }

    private AppUser admin(String prefix) {
        return user(prefix, AppUserRole.REGISTRY_ADMIN);
    }

    private AppUser enrolledInvestor() {
        AppUser u = user("enrolled", AppUserRole.INVESTOR);
        u.setTotpSecret(secretStore.encrypt(u.getId(), StepUpTokenIssuer.generateBase32Secret()));
        u.setTotpEnabled(true);
        return users.save(u);
    }

    private String session(AppUser user) {
        return TestJwt.mint(SECRET, user.getId(), false, null, null, user.getRoles().iterator().next().name());
    }

    private HttpHeaders headers(String bearer, String approverToken) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(bearer);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        if (approverToken != null) {
            h.set("X-Dual-Control-Token", approverToken);
        }
        return h;
    }

    private ResponseEntity<String> call(HttpMethod method, AppUser as, String path, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers(session(as), null)), String.class);
    }

    private ResponseEntity<String> post(AppUser as, String path, Object body) {
        return call(HttpMethod.POST, as, path, body);
    }

    private static JsonNode json(ResponseEntity<String> r) {
        return JSON.readTree(r.getBody());
    }

    /** The caller's own ordinary step-up token (the Bearer on the real, gated request). */
    private String stepUp(AppUser user) {
        ResponseEntity<StepUpResponse> r = rest.exchange(url("/api/v1/auth/step-up"), HttpMethod.POST,
                new HttpEntity<>(Map.of("code", "123456", "method", "TOTP"), headers(session(user), null)), StepUpResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().stepUpToken();
    }

    /** The real, gated request, sent the way the initiator's client sends it after claiming. */
    private ResponseEntity<String> realRequest(AppUser initiator, String path, Object body, String approverToken) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(stepUp(initiator), approverToken)),
                String.class);
    }

    private Map<String, Object> draft(String action, String path, Object body) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("action", action);
        d.put("method", "POST");
        d.put("path", path);
        if (body != null) {
            d.put("body", body);
        }
        return d;
    }

    private String create(AppUser requester, String action, String path, Object body) {
        ResponseEntity<String> r = post(requester, "/api/v1/approvals", draft(action, path, body));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
        return json(r).get("id").asString();
    }

    private ResponseEntity<String> approve(AppUser approver, String id) {
        return post(approver, "/api/v1/approvals/" + id + "/approve", Map.of("code", "123456", "note", "reviewed"));
    }

    private ResponseEntity<String> claim(AppUser requester, String id) {
        return post(requester, "/api/v1/approvals/" + id + "/claim", Map.of());
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM approval_request WHERE id = ?::uuid", String.class, id);
    }

    private static String resetPath(AppUser target) {
        return "/api/v1/admin/users/" + target.getId() + "/totp-reset";
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("request -> inbox -> approve (TOTP) -> claim -> the real endpoint accepts the token once")
    void happyPathThroughTheRealFilterChain() {
        AppUser initiator = admin("initiator");
        AppUser approver = user("officer", AppUserRole.COMPLIANCE_OFFICER);
        AppUser target = enrolledInvestor();
        String id = create(initiator, "TOTP_RESET", resetPath(target), null);

        // The approver's inbox shows it; the requester's inbox does not.
        ResponseEntity<String> pending = call(HttpMethod.GET, approver, "/api/v1/approvals/pending?size=100", null);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pending.getBody()).contains(id);
        assertThat(json(call(HttpMethod.GET, approver, "/api/v1/approvals/pending/count", null)).get("count").asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(call(HttpMethod.GET, initiator, "/api/v1/approvals/pending?size=100", null).getBody()).doesNotContain(id);

        ResponseEntity<String> approved = approve(approver, id);
        assertThat(approved.getStatusCode()).as(approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(approved).get("status").asString()).isEqualTo("APPROVED");
        assertThat(json(call(HttpMethod.GET, initiator, "/api/v1/approvals/" + id, null)).get("approverUserId").asString())
                .isEqualTo(approver.getId().toString());

        ResponseEntity<String> claimed = claim(initiator, id);
        assertThat(claimed.getStatusCode()).as(claimed.getBody()).isEqualTo(HttpStatus.OK);
        String token = json(claimed).get("approvalToken").asString();
        assertThat(json(claimed).get("target").asString()).isEqualTo("POST " + resetPath(target));
        assertThat(status(id)).isEqualTo("CLAIMED");

        assertThat(realRequest(initiator, resetPath(target), null, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(users.findById(target.getId()).orElseThrow().isTotpEnabled()).as("the guarded action ran").isFalse();
        assertThat(realRequest(initiator, resetPath(target), null, token).getStatusCode())
                .as("the token is single use").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(claim(initiator, id).getStatusCode()).as("a claim is single use").isEqualTo(HttpStatus.CONFLICT);

        awaitAudit(id, "APPROVAL_REQUEST_CREATED", "APPROVAL_REQUEST_APPROVED", "APPROVAL_REQUEST_CLAIMED");
    }

    private void awaitAudit(String id, String... eventTypes) {
        Instant deadline = Instant.now().plusSeconds(20);
        int found = 0;
        while (Instant.now().isBefore(deadline)) {
            found = jdbc.queryForObject("SELECT count(DISTINCT event_type) FROM audit_event WHERE subject_id = ?::uuid AND event_type = ANY (?)",
                    Integer.class, id, eventTypes);
            if (found == eventTypes.length) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(found).as("audit entries " + List.of(eventTypes)).isEqualTo(eventTypes.length);
    }

    // ── binding ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the minted token is bound to the exact request: a changed body, path or other target is refused by the real endpoint")
    void tokenIsBoundToTheApprovedRequest() {
        AppUser initiator = admin("initiator");
        AppUser approver = admin("approver");
        UUID entity = UUID.randomUUID();
        String path = "/api/v1/entities/" + entity + "/kyc/approve";
        Map<String, Object> approvedBody = Map.of("overrideNote", "Checklist reviewed, risk accepted");

        // Body changed after approval.
        String id = create(initiator, "KYC_APPROVE", path, approvedBody);
        assertThat(approve(approver, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = json(claim(initiator, id)).get("approvalToken").asString();
        assertThat(realRequest(initiator, path, Map.of("overrideNote", "Something the approver never saw"), token).getStatusCode())
                .as("tampered body").isEqualTo(HttpStatus.FORBIDDEN);
        // A refused attempt does not burn the approval; the approved body passes the dual-control gate (the
        // business layer then says whatever it says about a made-up entity - not 403).
        assertThat(realRequest(initiator, path, approvedBody, token).getStatusCode())
                .as("approved body").isNotEqualTo(HttpStatus.FORBIDDEN);

        // Same reason, other entity.
        String id2 = create(initiator, "KYC_APPROVE", path, approvedBody);
        assertThat(approve(approver, id2).getStatusCode()).isEqualTo(HttpStatus.OK);
        String token2 = json(claim(initiator, id2)).get("approvalToken").asString();
        assertThat(realRequest(initiator, "/api/v1/entities/" + UUID.randomUUID() + "/kyc/approve", approvedBody, token2)
                .getStatusCode()).as("other target").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a claimed token is useless to anybody but the requester who claimed it")
    void tokenIsBoundToTheRequester() {
        AppUser initiator = admin("initiator");
        AppUser thief = admin("thief");
        AppUser approver = admin("approver");
        AppUser target = enrolledInvestor();
        String id = create(initiator, "TOTP_RESET", resetPath(target), null);
        approve(approver, id);
        String token = json(claim(initiator, id)).get("approvalToken").asString();

        assertThat(realRequest(thief, resetPath(target), null, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findById(target.getId()).orElseThrow().isTotpEnabled()).isTrue();
        assertThat(realRequest(initiator, resetPath(target), null, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("the minted token is no credential: as the Bearer it is refused on every endpoint")
    void tokenIsNeverABearer() {
        AppUser initiator = admin("initiator");
        AppUser approver = admin("approver");
        String id = create(initiator, "TOTP_RESET", resetPath(enrolledInvestor()), null);
        approve(approver, id);
        String token = json(claim(initiator, id)).get("approvalToken").asString();

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        assertThat(rest.exchange(url("/api/v1/approvals/mine"), HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── who may do what ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the requester cannot approve or reject their own request, even as REGISTRY_ADMIN with a valid code")
    void requesterCannotDecideOwnRequest() {
        AppUser initiator = admin("initiator");
        String id = create(initiator, "TOTP_RESET", resetPath(enrolledInvestor()), null);

        assertThat(approve(initiator, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(initiator, "/api/v1/approvals/" + id + "/reject", Map.of("note", "x")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("PENDING");
        // ... and the table refuses it even if the application ever tried.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE approval_request SET status = 'APPROVED', approver_user_id = requester_user_id WHERE id = ?::uuid", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("only eligible approvers see the inbox; strangers cannot read, decide or claim someone else's request")
    void accessIsRestricted() {
        AppUser initiator = admin("initiator");
        AppUser approver = admin("approver");
        AppUser investor = user("investor", AppUserRole.INVESTOR);
        String id = create(initiator, "TOTP_RESET", resetPath(enrolledInvestor()), null);

        assertThat(call(HttpMethod.GET, investor, "/api/v1/approvals/pending", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, investor, "/api/v1/approvals/pending/count", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, investor, "/api/v1/approvals/" + id, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(approve(investor, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, approver, "/api/v1/approvals/" + id, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, initiator, "/api/v1/approvals/mine", null).getBody()).contains(id);

        assertThat(approve(approver, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claim(approver, id).getStatusCode()).as("the approver can never claim").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(claim(investor, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(approver, "/api/v1/approvals/" + id + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("an approver disabled after approving yields no token at claim time")
    void claimRechecksTheApprover() {
        AppUser initiator = admin("initiator");
        AppUser approver = admin("approver");
        String id = create(initiator, "TOTP_RESET", resetPath(enrolledInvestor()), null);
        approve(approver, id);

        approver.setEnabled(false);
        users.save(approver);

        assertThat(claim(initiator, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("APPROVED");
    }

    // ── what may be requested ────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown action, an action of another route and a non-API path are refused")
    void onlyRealFourEyesRequestsAreAccepted() {
        AppUser initiator = admin("initiator");
        String path = resetPath(enrolledInvestor());

        assertThat(post(initiator, "/api/v1/approvals", draft("NOT_A_REAL_ACTION", path, null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(initiator, "/api/v1/approvals", draft("KYC_APPROVE", path, null)).getStatusCode())
                .as("a real action, but not that route's").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(initiator, "/api/v1/approvals", draft("TOTP_RESET", "/actuator/env", null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(initiator, "/api/v1/approvals", draft("WALLET_IMPORT_RAW", "/api/v1/wallets/import", Map.of("privateKey", "0x01")))
                .getStatusCode()).as("secret-bearing body is never copied into a request").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_request WHERE requester_user_id = ?::uuid",
                Integer.class, initiator.getId().toString())).isZero();
    }

    @Test
    @DisplayName("a body the digest cannot represent unambiguously (repeated key) is refused when filing")
    void ambiguousBodyIsRefused() {
        AppUser initiator = admin("initiator");
        HttpHeaders h = headers(session(initiator), null);
        String raw = "{\"action\":\"KYC_APPROVE\",\"method\":\"POST\",\"path\":\"/api/v1/entities/" + UUID.randomUUID()
                + "/kyc/approve\",\"body\":{\"overrideNote\":\"a\",\"overrideNote\":\"b\"}}";
        assertThat(rest.exchange(url("/api/v1/approvals"), HttpMethod.POST, new HttpEntity<>(raw, h), String.class)
                .getStatusCode().is4xxClientError()).isTrue();
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a cancelled request can neither be approved nor claimed; a rejected one neither")
    void cancelledAndRejectedAreDead() {
        AppUser initiator = admin("initiator");
        AppUser approver = admin("approver");
        String path = resetPath(enrolledInvestor());

        String cancelled = create(initiator, "TOTP_RESET", path, null);
        assertThat(post(initiator, "/api/v1/approvals/" + cancelled + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approve(approver, cancelled).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(claim(initiator, cancelled).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String approvedThenCancelled = create(initiator, "TOTP_RESET", path, null);
        approve(approver, approvedThenCancelled);
        assertThat(post(initiator, "/api/v1/approvals/" + approvedThenCancelled + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claim(initiator, approvedThenCancelled).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String rejected = create(initiator, "TOTP_RESET", path, null);
        assertThat(post(approver, "/api/v1/approvals/" + rejected + "/reject", Map.of("note", "no")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approve(approver, rejected).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(claim(initiator, rejected).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(initiator, "/api/v1/approvals/" + rejected + "/cancel", Map.of()).getStatusCode())
                .as("a decided request cannot be cancelled").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("expired requests cannot be approved or claimed, and the sweep marks them EXPIRED")
    void expiredRequestsAreDeadAndSwept() {
        AppUser initiator = admin("initiator");
        AppUser approver = admin("approver");
        String path = resetPath(enrolledInvestor());

        String stalePending = create(initiator, "TOTP_RESET", path, null);
        jdbc.update("UPDATE approval_request SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", stalePending);
        assertThat(approve(approver, stalePending).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(stalePending)).isEqualTo("PENDING");

        String staleApproved = create(initiator, "TOTP_RESET", path, null);
        approve(approver, staleApproved);
        jdbc.update("UPDATE approval_request SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", staleApproved);
        assertThat(claim(initiator, staleApproved).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(staleApproved)).isEqualTo("APPROVED");

        String fresh = create(initiator, "TOTP_RESET", path, null);
        assertThat(service.expireStale()).isGreaterThanOrEqualTo(2);
        assertThat(status(stalePending)).isEqualTo("EXPIRED");
        assertThat(status(staleApproved)).isEqualTo("EXPIRED");
        assertThat(status(fresh)).isEqualTo("PENDING");
        assertThat(call(HttpMethod.GET, approver, "/api/v1/approvals/pending?size=100", null).getBody())
                .contains(fresh).doesNotContain(stalePending);
        awaitAudit(stalePending, "APPROVAL_REQUEST_EXPIRED");
    }

    // ── races ────────────────────────────────────────────────────────────────

    private List<HttpStatus> race(List<Callable<ResponseEntity<String>>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> c : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<HttpStatus> out = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : futures) {
                out.add(HttpStatus.valueOf(f.get().getStatusCode().value()));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("concurrent approvals by several approvers resolve to exactly one winner")
    void concurrentApprovalsHaveOneWinner() throws Exception {
        AppUser initiator = admin("initiator");
        String id = create(initiator, "TOTP_RESET", resetPath(enrolledInvestor()), null);
        List<AppUser> approvers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            approvers.add(admin("racer" + i));
        }
        List<Callable<ResponseEntity<String>>> calls = new ArrayList<>();
        approvers.forEach(a -> calls.add(() -> approve(a, id)));

        List<HttpStatus> statuses = race(calls);

        assertThat(statuses.stream().filter(s -> s == HttpStatus.OK)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == HttpStatus.CONFLICT)).hasSize(5);
        assertThat(status(id)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("an approve racing a reject, and an approve racing a cancel, end in one consistent state")
    void approveVersusRejectAndCancel() throws Exception {
        AppUser initiator = admin("initiator");
        AppUser a1 = admin("a1");
        AppUser a2 = admin("a2");
        String path = resetPath(enrolledInvestor());

        String id = create(initiator, "TOTP_RESET", path, null);
        List<HttpStatus> s = race(List.of(() -> approve(a1, id),
                () -> post(a2, "/api/v1/approvals/" + id + "/reject", Map.of("note", "no"))));
        assertThat(s.stream().filter(x -> x == HttpStatus.OK)).hasSize(1);
        assertThat(status(id)).isIn("APPROVED", "REJECTED");

        String id2 = create(initiator, "TOTP_RESET", path, null);
        List<HttpStatus> s2 = race(List.of(() -> approve(a1, id2),
                () -> post(initiator, "/api/v1/approvals/" + id2 + "/cancel", Map.of())));
        assertThat(s2.stream().filter(x -> x == HttpStatus.OK).count()).isBetween(1L, 2L);
        String end = status(id2);
        assertThat(end).isIn("APPROVED", "CANCELLED");
        if (end.equals("CANCELLED")) {
            assertThat(claim(initiator, id2).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    @DisplayName("concurrent claims of one approval yield exactly one token")
    void concurrentClaimsHaveOneWinner() throws Exception {
        AppUser initiator = admin("initiator");
        AppUser approver = admin("approver");
        String id = create(initiator, "TOTP_RESET", resetPath(enrolledInvestor()), null);
        approve(approver, id);

        List<Callable<ResponseEntity<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> claim(initiator, id));
        }
        List<HttpStatus> statuses = race(calls);

        assertThat(statuses.stream().filter(s -> s == HttpStatus.OK)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == HttpStatus.CONFLICT)).hasSize(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_request WHERE id = ?::uuid AND status = 'CLAIMED' AND claim_jti IS NOT NULL",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a requester holds at most the configured number of open requests")
    void openRequestsAreCapped() {
        AppUser initiator = admin("capped");
        String path = resetPath(enrolledInvestor());
        int cap = 20;
        for (int i = 0; i < cap; i++) {
            create(initiator, "TOTP_RESET", path, null);
        }
        assertThat(post(initiator, "/api/v1/approvals", draft("TOTP_RESET", path, null)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
