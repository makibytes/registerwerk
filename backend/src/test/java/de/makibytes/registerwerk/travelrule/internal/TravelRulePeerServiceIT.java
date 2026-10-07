package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.MigratedDb;
import de.makibytes.registerwerk.travelrule.events.TravelRuleControlEvent;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Wave 5b item 1: re-registering a peer must not reactivate a DISABLED one, and "rotated" must be truthful. */
@Testcontainers
@DisplayName("Travel Rule peer re-registration against real PostgreSQL")
class TravelRulePeerServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(de.makibytes.registerwerk.TestPostgres.IMAGE);

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = MigratedDb.migrate(postgres).jdbc();
    }

    private final KekProvider kek = new KekProvider() {
        public String name() { return "test"; }
        public byte[] wrap(byte[] d) { return d.clone(); }
        public byte[] unwrap(byte[] d) { return d.clone(); }
    };

    @Test
    void reRegisteringRotatesTheKeyButKeepsADisabledPeerDisabledUntilExplicitEnable() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        TravelRulePeerService service = new TravelRulePeerService(jdbc, kek, events, new TravelRuleProperties(),
                Clock.systemUTC());
        UUID actor = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        TravelRulePeerService.CreatedPeer first = service.register("did:example:p1", "P1", null, actor, "REGISTRY_ADMIN", approver);
        assertThat(first.peer().status()).isEqualTo("ACTIVE");
        service.disable("did:example:p1", actor, "REGISTRY_ADMIN", approver);

        TravelRulePeerService.CreatedPeer second = service.register("did:example:p1", "P1", null, actor, "REGISTRY_ADMIN", approver);

        assertThat(second.peer().status()).as("re-registration must not reactivate").isEqualTo("DISABLED");
        assertThat(second.hmacKey()).isNotEqualTo(first.hmacKey());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        var created = captor.getAllValues().stream().filter(TravelRuleControlEvent.class::isInstance)
                .map(TravelRuleControlEvent.class::cast)
                .filter(e -> TravelRuleControlEvent.PEER_CREATED.equals(e.eventType())).toList();
        assertThat(created).hasSize(2);
        assertThat(created.get(0).payload().get("rotated")).as("first registration is not a rotation").isEqualTo(false);
        assertThat(created.get(1).payload().get("rotated")).isEqualTo(true);

        service.enable("did:example:p1", actor, "REGISTRY_ADMIN", approver);
        assertThat(service.list().stream().filter(p -> p.vaspId().equals("did:example:p1")).findFirst().orElseThrow()
                .status()).isEqualTo("ACTIVE");
    }
}
