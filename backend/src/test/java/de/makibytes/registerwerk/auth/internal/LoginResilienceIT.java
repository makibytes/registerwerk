package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 0a C4 against a deliberately tiny connection pool: an unauthenticated flood of failing logins must
 * neither starve the pool (a login used to hold one connection through BCrypt and then ask for a second
 * one to record the failure) nor sleep on a request thread; throttled callers get 429 + Retry-After.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Login under load and throttling (Wave 0a C4)")
class LoginResilienceIT {

    private static final String PASSWORD = "Sup3rSecret!pw";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.entra-enabled", () -> "false");
        // Smaller than the number of parallel logins, and a short wait: starvation shows up as 5xx fast.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
    }

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired LoginAttemptLimiter limiter;
    @LocalServerPort int port;

    @BeforeEach
    void cleanThrottle() {
        jdbc.update("DELETE FROM login_attempt");
        limiter.purgeStale();
    }

    private AppUser newUser(String prefix) {
        AppUser u = new AppUser();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@test.local");
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setRoles(Set.of(AppUserRole.REGISTRY_ADMIN));
        return users.save(u);
    }

    private ResponseEntity<String> login(String email, String password, String clientIp) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Forwarded-For", clientIp); // honoured: the test client is a trusted (loopback) proxy
        return rest.exchange("http://localhost:" + port + "/api/v1/public/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", password), h), String.class);
    }

    @Test
    @DisplayName("C4: a flood of parallel failing logins larger than the pool never produces a 5xx")
    void parallelFailedLoginsDoNotExhaustThePool() throws Exception {
        int parallel = 24;
        ExecutorService executor = Executors.newFixedThreadPool(parallel);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < parallel; i++) {
                String email = "nobody" + i + "-" + UUID.randomUUID() + "@test.local";
                String ip = "198.51.100." + (i + 1);
                results.add(executor.submit(() -> {
                    go.await();
                    return login(email, "wrong-password", ip).getStatusCode().value();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(90, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("every flooded login is answered 401/429, never a pool timeout")
                    .allMatch(s -> s == 401 || s == 429);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("C4: after the lockout threshold the pair is answered 429 with Retry-After - for a real and an unknown account alike")
    void lockedPairGets429WithRetryAfter() throws Exception {
        AppUser real = newUser("victim");
        String unknown = "ghost-" + UUID.randomUUID() + "@test.local";
        for (int i = 0; i < 5; i++) {
            assertThat(login(real.getEmail(), "wrong", "203.0.113.10").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(login(unknown, "wrong", "203.0.113.10").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> lockedReal = login(real.getEmail(), PASSWORD, "203.0.113.10");
        ResponseEntity<String> lockedUnknown = login(unknown, PASSWORD, "203.0.113.10");

        assertThat(lockedReal.getStatusCode()).as("right password, locked pair").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lockedUnknown.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(lockedReal.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 15 * 60L);
        assertThat(lockedUnknown.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        // The pair lock is per address: from another one the account is only subject to the short account-wide
        // delay (1 s after five failures), after which the real user gets in.
        Thread.sleep(1_200);
        assertThat(login(real.getEmail(), PASSWORD, "203.0.113.11").getStatusCode())
                .as("another address is not locked out").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("C4: failures spread over many addresses delay the account for a few seconds (429 + Retry-After) but never lock it")
    void distributedFailuresDelayButNeverLock() throws Exception {
        AppUser user = newUser("distributed");
        for (int i = 0; i < 6; i++) {
            limiter.recordFailure(user.getEmail(), "192.0.2." + (10 + i));
        }

        long start = System.nanoTime();
        ResponseEntity<String> early = login(user.getEmail(), PASSWORD, "192.0.2.99");
        long tookMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(tookMillis).as("answered at once, no request thread parked in a sleep").isLessThan(900);
        long retryAfter = Long.parseLong(early.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 5L);

        Thread.sleep(retryAfter * 1000 + 200);
        assertThat(login(user.getEmail(), PASSWORD, "192.0.2.99").getStatusCode())
                .as("the real user gets in once the delay has passed").isEqualTo(HttpStatus.OK);
    }
}
