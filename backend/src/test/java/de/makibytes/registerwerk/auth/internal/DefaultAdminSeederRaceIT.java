package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 7A-12: two replicas seeding the first admin at once must both finish, leaving exactly one account. */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Default admin seeder race")
class DefaultAdminSeederRaceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
    }

    @Autowired DefaultAdminSeeder seeder;
    @Autowired RegisterwerkAuthProperties props;
    @Autowired JdbcTemplate jdbc;

    @Test
    void parallelSeedersCreateExactlyOneAdmin() throws Exception {
        props.getDefaultAdmin().setEmail("race-admin@test.local");
        props.getDefaultAdmin().setPassword("Sup3rSecret!-race");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        java.util.List<Future<?>> runs = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(pool.submit(() -> { go.await(); seeder.run(null); return null; }));
        }
        go.countDown();
        for (Future<?> f : runs) {
            f.get(); // none may throw
        }
        pool.shutdown();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
    }
}
