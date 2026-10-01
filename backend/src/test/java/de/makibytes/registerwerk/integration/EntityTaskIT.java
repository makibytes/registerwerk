package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskRepository;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.internal.EntityTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 6 K8 (V40): entity tasks are persisted, idempotent while OPEN, and re-openable once DONE. */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Entity lifecycle tasks (V40)")
class EntityTaskIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        r.add("registerwerk.auth.dev-secret", () -> "integration-test-jwt-secret-32-bytes!!");
    }

    @Autowired EntityTaskPort port;
    @Autowired EntityTaskService service;
    @Autowired EntityTaskRepository tasks;
    @Autowired LegalEntityRepository entities;

    @Test
    @DisplayName("open is idempotent per (entity, kind, ref) while OPEN; DONE tasks stay and a new one can be raised")
    void idempotentOpen() {
        LegalEntity e = new LegalEntity();
        e.setCurrentName("Task Test AG");
        e.setType(EntityType.ISSUER);
        e.setStatus(EntityStatus.ACTIVE);
        e.setEntityNumber("ENT-TASK-" + UUID.randomUUID().toString().substring(0, 8));
        e = entities.saveAndFlush(e);

        assertThat(port.open(e.getId(), "ISSUER_ASSET_LIVE", "a1", "d", null)).isTrue();
        assertThat(port.open(e.getId(), "ISSUER_ASSET_LIVE", "a1", "d", null)).isFalse();
        assertThat(service.listForEntity(e.getId())).hasSize(1);

        EntityTask done = service.complete(service.listForEntity(e.getId()).get(0).getId(), UUID.randomUUID(), "handled");
        assertThat(done.getStatus()).isEqualTo(EntityTask.Status.DONE);
        assertThat(port.open(e.getId(), "ISSUER_ASSET_LIVE", "a1", "d", null)).isTrue();
        assertThat(tasks.countByStatus(EntityTask.Status.OPEN)).isGreaterThanOrEqualTo(1);
    }
}
