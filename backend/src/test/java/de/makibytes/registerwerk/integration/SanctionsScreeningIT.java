package de.makibytes.registerwerk.integration;

import de.makibytes.registerwerk.screening.internal.ScreeningRun;
import de.makibytes.registerwerk.screening.internal.ScreeningRunRepository;
import de.makibytes.registerwerk.screening.internal.ScreeningService;
import de.makibytes.registerwerk.screening.internal.ScreeningStatus;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@DisplayName("Sanctions screening integration tests")
class SanctionsScreeningIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("registerwerk.auth.default-admin.email", () -> "admin@test.local");
        registry.add("registerwerk.auth.default-admin.password", () -> "Sup3rSecret!");
    }

    @Autowired
    private ScreeningService screeningService;

    @Autowired
    private ScreeningRunRepository runRepository;

    @Test
    @DisplayName("screenEntity — creates a screening run for a known entity")
    void screenEntity_createsScreeningRun() {
        UUID entityId = UUID.randomUUID();

        ScreeningRun result = screeningService.screenEntity(
                entityId, "Test Entity GmbH", "DE", null, ScreeningTrigger.ENTITY_ONBOARDING);

        assertThat(result).isNotNull();
        assertThat(result.getEntityId()).isEqualTo(entityId);
        // OpenSanctions adapter is active (or NOOP if unavailable) — status is CLEAR or ERROR
        assertThat(result.getStatus()).isIn(ScreeningStatus.CLEAR, ScreeningStatus.HIT, ScreeningStatus.ERROR);
    }

    @Test
    @DisplayName("screenEntity — run is persisted in DB")
    void screenEntity_runPersistedInDb() {
        UUID entityId = UUID.randomUUID();

        screeningService.screenEntity(
                entityId, "Another Test Corp", "LU", "549300ABCDEFG12345", ScreeningTrigger.KYC_SUBMISSION);

        ScreeningRun persisted = runRepository.findTopByEntityIdOrderByStartedAtDesc(entityId);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getEntityId()).isEqualTo(entityId);
        assertThat(persisted.getTriggerType()).isEqualTo(ScreeningTrigger.KYC_SUBMISSION);
        assertThat(persisted.getCompletedAt()).isNotNull();
    }
}
