package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.shared.ColumnSecretInventory;
import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.EnvelopeSecretInventory;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.shared.SecretColumn;
import de.makibytes.registerwerk.wallet.events.KekRewrapCompletedEvent;
import de.makibytes.registerwerk.wallet.events.KekVersionRetiredEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * KEK re-wrap across every envelope-encrypted secret type with two real KEK versions (the env-var
 * provider's key ring): secrets are written under v1, the platform rotates to v2, the re-wrap moves
 * every ciphertext, and a version can only be retired once nothing references it any more.
 */
class KekRewrapServiceTest {

    private static final String K1 = "k1-0123456789-0123456789-0123456789-aaaa";
    private static final String K2 = "k2-0123456789-0123456789-0123456789-bbbb";
    private static final String K9 = "k9-0123456789-0123456789-0123456789-zzzz";

    private EnvVarKekProvider v1Only;
    private EnvVarKekProvider v2WithV1;
    private final Map<String, String> totp = new TreeMap<>();
    private final Map<String, String> webhook = new TreeMap<>();
    private final Map<String, String> webhookPrev = new TreeMap<>();
    private ApplicationEventPublisher events;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        v1Only = provider("v1", K1, Map.of());
        v2WithV1 = provider("v2", K2, Map.of("v1", K1));
        events = mock(ApplicationEventPublisher.class);
        meters = new SimpleMeterRegistry();
        EnvelopeCipher c1 = new EnvelopeCipher(v1Only);
        totp.put("u1", c1.encrypt("TOTPSECRET1", "totp-secret:u1"));
        totp.put("u2", c1.encrypt("TOTPSECRET2", "totp-secret:u2"));
        webhook.put("w1", c1.encrypt("whsec1", "webhook-subscription:w1"));
        webhookPrev.put("w1", c1.encrypt("whsec0", "webhook-subscription:w1"));
    }

    private static EnvVarKekProvider provider(String version, String key, Map<String, String> previous) {
        WalletProperties p = new WalletProperties();
        p.setMasterKey(key);
        p.setMasterKeyVersion(version);
        p.setPreviousMasterKeys(previous);
        return new EnvVarKekProvider(p);
    }

    private KekRewrapService service(EnvVarKekProvider kek) {
        EnvelopeCipher cipher = new EnvelopeCipher(kek);
        EnvelopeSecretInventory totpInv = new ColumnSecretInventory("TOTP_SECRET", cipher, List.of(column(totp)), 1);
        EnvelopeSecretInventory whInv = new ColumnSecretInventory("WEBHOOK_SECRET", cipher,
                List.of(column(webhook), column(webhookPrev)), 1);
        return new KekRewrapService(List.of(totpInv, whInv), kek, config -> Optional.of(() -> { }), meters,
                events, new KekRewrapProperties());
    }

    private static SecretColumn column(Map<String, String> store) {
        return new SecretColumn() {
            @Override public List<Row> page(String afterKey, int limit) {
                return store.entrySet().stream()
                        .filter(e -> afterKey == null || e.getKey().compareTo(afterKey) > 0)
                        .limit(limit).map(e -> new Row(e.getKey(), e.getValue())).toList();
            }
            @Override public boolean replace(String key, String expected, String replacement) {
                return store.replace(key, expected, replacement);
            }
        };
    }

    private void assertAllDecryptableOnV2Only() {
        EnvelopeCipher v2Only = new EnvelopeCipher(provider("v2", K2, Map.of()));
        assertThat(v2Only.decrypt(totp.get("u1"), "totp-secret:u1")).isEqualTo("TOTPSECRET1");
        assertThat(v2Only.decrypt(totp.get("u2"), "totp-secret:u2")).isEqualTo("TOTPSECRET2");
        assertThat(v2Only.decrypt(webhook.get("w1"), "webhook-subscription:w1")).isEqualTo("whsec1");
        assertThat(v2Only.decrypt(webhookPrev.get("w1"), "webhook-subscription:w1")).isEqualTo("whsec0");
    }

    @Test
    @DisplayName("secrets written under v1, rotate to v2, re-wrap: all decryptable and recorded on v2")
    void rewrapMovesEverySecretToActiveVersion() {
        KekRewrapService svc = service(v2WithV1);
        assertThat(svc.scan()).containsEntry("TOTP_SECRET", Map.of("v1", 2L))
                .containsEntry("WEBHOOK_SECRET", Map.of("v1", 2L));
        assertThat(meters.get("registerwerk.kek.secrets.on.old.version").tag("type", "TOTP_SECRET").gauge().value())
                .isEqualTo(2.0);

        KekRewrapService.RewrapReport report = svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(report.byType().get("TOTP_SECRET").rewrapped()).isEqualTo(2);
        assertThat(report.byType().get("WEBHOOK_SECRET").rewrapped()).isEqualTo(2);
        assertThat(report.failed()).isZero();
        assertThat(svc.scan()).containsEntry("TOTP_SECRET", Map.of("v2", 2L))
                .containsEntry("WEBHOOK_SECRET", Map.of("v2", 2L));
        assertThat(meters.get("registerwerk.kek.secrets.on.old.version").tag("type", "WEBHOOK_SECRET").gauge().value())
                .isZero();
        assertAllDecryptableOnV2Only();

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(evt.capture());
        assertThat(evt.getValue()).isInstanceOf(KekRewrapCompletedEvent.class);
        assertThat(((KekRewrapCompletedEvent) evt.getValue()).payload().toString()).doesNotContain("TOTPSECRET");
    }

    @Test
    @DisplayName("re-wrap is idempotent: a second run changes nothing")
    void rewrapIsIdempotent() {
        KekRewrapService svc = service(v2WithV1);
        svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN");
        String before = totp.get("u1");

        KekRewrapService.RewrapReport second = svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(second.rewrapped()).isZero();
        assertThat(second.failed()).isZero();
        assertThat(totp.get("u1")).isEqualTo(before);
        assertAllDecryptableOnV2Only();
    }

    @Test
    @DisplayName("one unrecoverable row does not stop the batch and is counted")
    void failureOfOneRowIsCountedAndIsolated() {
        totp.put("u0", new EnvelopeCipher(provider("v1", K9, Map.of())).encrypt("LOST", "totp-secret:u0"));
        KekRewrapService svc = service(v2WithV1);

        KekRewrapService.RewrapReport report = svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(report.byType().get("TOTP_SECRET").failed()).isEqualTo(1);
        assertThat(report.byType().get("TOTP_SECRET").rewrapped()).isEqualTo(2);
        assertThat(report.byType().get("WEBHOOK_SECRET").rewrapped()).isEqualTo(2);
        assertThat(meters.get("registerwerk.kek.rewrap.failures").tag("type", "TOTP_SECRET").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("retiring v1 is refused (409) while any ciphertext still references it, allowed after re-wrap")
    void retireGuard() {
        String oldTotp = totp.get("u1");
        KekRewrapService svc = service(v2WithV1);

        assertThatThrownBy(() -> svc.retireVersion("v1", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("TOTP_SECRET=2").hasMessageContaining("WEBHOOK_SECRET=2");
        // still decryptable: the refusal changed nothing
        assertThat(new EnvelopeCipher(v2WithV1).decrypt(oldTotp, "totp-secret:u1")).isEqualTo("TOTPSECRET1");

        svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN");
        svc.retireVersion("v1", UUID.randomUUID(), "REGISTRY_ADMIN");

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(evt.capture());
        assertThat(evt.getAllValues().get(1)).isInstanceOf(KekVersionRetiredEvent.class);
        // v1 is gone from the ring: the pre-re-wrap ciphertext no longer opens, the re-wrapped one does
        assertThatThrownBy(() -> new EnvelopeCipher(v2WithV1).decrypt(oldTotp, "totp-secret:u1"))
                .isInstanceOf(IllegalStateException.class);
        assertAllDecryptableOnV2Only();
    }

    @Test
    @DisplayName("the active version and unknown versions cannot be retired")
    void retireRejectsActiveAndUnknown() {
        KekRewrapService svc = service(v2WithV1);
        svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN");
        assertThatThrownBy(() -> svc.retireVersion("v2", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("active");
        assertThatThrownBy(() -> svc.retireVersion("v7", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(de.makibytes.registerwerk.shared.EntityNotFoundException.class);
    }

    @Test
    @DisplayName("an inventory that cannot be scanned makes the retire guard fail closed")
    void retireFailsClosedWhenInventoryUnavailable() {
        EnvelopeSecretInventory broken = new EnvelopeSecretInventory() {
            @Override public String type() { return "BROKEN"; }
            @Override public Map<String, Long> countByKekVersion() { throw new IllegalStateException("db down"); }
            @Override public RewrapOutcome rewrapStale(int pageSize) { return new RewrapOutcome(0, 0); }
        };
        KekRewrapService svc = new KekRewrapService(List.of(broken), v2WithV1, c -> Optional.of(() -> { }), meters,
                events, new KekRewrapProperties());
        assertThatThrownBy(() -> svc.retireVersion("v1", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("BROKEN");
    }

    @Test
    @DisplayName("a concurrent re-wrap (lock held elsewhere) is refused with 409")
    void concurrentRunRefused() {
        KekRewrapService svc = new KekRewrapService(List.of(), v2WithV1, c -> Optional.empty(), meters, events,
                new KekRewrapProperties());
        assertThatThrownBy(() -> svc.rewrapAll(UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
