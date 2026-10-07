package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.MigratedDb;
import de.makibytes.registerwerk.TestPostgres;
import de.makibytes.registerwerk.notification.internal.SecureLinkInventory;
import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.stepup.internal.TotpSecretInventory;
import de.makibytes.registerwerk.travelrule.internal.TravelRulePeerKeyInventory;
import de.makibytes.registerwerk.webhook.internal.WebhookSecretInventory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The real per-module inventories against the migrated schema: secrets written under KEK v1 in app_user,
 * webhook_subscription (both columns), travel_rule_peer and an undelivered secure link are found, re-wrapped
 * (the secure link, which lives in a Modulith event publication, is counted but left alone) and the retire
 * guard follows the data.
 */
@Testcontainers
class KekRewrapSchemaIT {

    private static final String K1 = "k1-0123456789-0123456789-0123456789-aaaa";
    private static final String K2 = "k2-0123456789-0123456789-0123456789-bbbb";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestPostgres.IMAGE);

    private static EnvVarKekProvider provider(String version, String key, Map<String, String> previous) {
        WalletProperties p = new WalletProperties();
        p.setMasterKey(key);
        p.setMasterKeyVersion(version);
        p.setPreviousMasterKeys(previous);
        return new EnvVarKekProvider(p);
    }

    @Test
    @DisplayName("TOTP, webhook (current + previous), Travel Rule peer keys re-wrapped in the database; secure link blocks retirement until delivered")
    void realTablesRewrapAndGuard() {
        JdbcTemplate jdbc = MigratedDb.migrate(postgres).jdbc();
        EnvelopeCipher v1 = new EnvelopeCipher(provider("v1", K1, Map.of()));
        UUID user = UUID.randomUUID();
        UUID hook = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, totp_secret, totp_secret_kid) VALUES (?, 'op@example.test', ?, 'old')",
                user, v1.encrypt("TOTPSECRET", "totp-secret:" + user));
        jdbc.update("INSERT INTO webhook_subscription (id, entity_id, url, secret, secret_previous_enc, event_types) "
                        + "VALUES (?, ?, 'https://hooks.example.test/x', ?, ?, '')", hook, UUID.randomUUID(),
                v1.encrypt("whsec-new", "webhook-subscription:" + hook), v1.encrypt("whsec-old", "webhook-subscription:" + hook));
        jdbc.update("INSERT INTO travel_rule_peer (vasp_id, legal_name, lei, hmac_key_ciphertext, key_kid, status) "
                        + "VALUES ('VASP-1', 'Peer AG', '529900T8BM49AURSDO55', ?, 'old', 'ACTIVE')",
                v1.encrypt("peer-hmac-key", "travel-rule-peer:VASP-1"));
        UUID publication = UUID.randomUUID();
        jdbc.update("INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date) "
                        + "VALUES (?, 'l', 'e', ?, now())", publication,
                "{\"inviteLink\":\"" + v1.encrypt("https://x/register/tok", "notification-link:" + user) + "\"}");

        EnvVarKekProvider v2 = provider("v2", K2, Map.of("v1", K1));
        List<de.makibytes.registerwerk.shared.EnvelopeSecretInventory> inventories = List.of(
                new TotpSecretInventory(jdbc, v2), new WebhookSecretInventory(jdbc, v2),
                new TravelRulePeerKeyInventory(jdbc, v2), new SecureLinkInventory(jdbc, v2));
        KekRewrapService svc = new KekRewrapService(inventories, v2, c -> Optional.of(() -> { }),
                new SimpleMeterRegistry(), mock(ApplicationEventPublisher.class), new KekRewrapProperties());

        assertThat(svc.scan()).containsEntry("TOTP_SECRET", Map.of("v1", 1L))
                .containsEntry("WEBHOOK_SECRET", Map.of("v1", 2L))
                .containsEntry("TRAVEL_RULE_PEER_KEY", Map.of("v1", 1L))
                .containsEntry("SECURE_LINK_IN_FLIGHT", Map.of("v1", 1L));

        KekRewrapService.RewrapReport report = svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(report.failed()).isZero();
        assertThat(report.rewrapped()).isEqualTo(4);
        EnvelopeCipher v2Only = new EnvelopeCipher(provider("v2", K2, Map.of()));
        assertThat(v2Only.decrypt(jdbc.queryForObject("SELECT totp_secret FROM app_user WHERE id = ?", String.class, user),
                "totp-secret:" + user)).isEqualTo("TOTPSECRET");
        assertThat(jdbc.queryForObject("SELECT totp_secret_kid FROM app_user WHERE id = ?", String.class, user))
                .isEqualTo("ENV_VAR_KEK");
        assertThat(v2Only.decrypt(jdbc.queryForObject("SELECT secret_previous_enc FROM webhook_subscription WHERE id = ?",
                String.class, hook), "webhook-subscription:" + hook)).isEqualTo("whsec-old");
        assertThat(v2Only.decrypt(jdbc.queryForObject("SELECT hmac_key_ciphertext FROM travel_rule_peer", String.class),
                "travel-rule-peer:VASP-1")).isEqualTo("peer-hmac-key");
        assertThat(svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN").rewrapped()).isZero();

        assertThatThrownBy(() -> svc.retireVersion("v1", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("SECURE_LINK_IN_FLIGHT=1");
        jdbc.update("UPDATE event_publication SET completion_date = now() WHERE id = ?", publication);
        assertThat(svc.retireVersion("v1", UUID.randomUUID(), "REGISTRY_ADMIN").disabledInProcess()).isTrue();
    }
}
