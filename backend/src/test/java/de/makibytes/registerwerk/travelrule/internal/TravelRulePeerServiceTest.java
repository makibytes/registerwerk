package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 6-29: request authenticity per peer (HMAC), freshness window and replay cache. */
class TravelRulePeerServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KekProvider kek = new KekProvider() {
        public String name() { return "test"; }
        public byte[] wrap(byte[] d) { return d.clone(); }
        public byte[] unwrap(byte[] d) { return d.clone(); }
    };
    private TravelRulePeerService service;
    private String storedCiphertext;
    private final byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
    private String key;

    @BeforeEach
    void setUp() {
        service = new TravelRulePeerService(jdbc, kek, mock(ApplicationEventPublisher.class),
                new TravelRuleProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
        // capture the encrypted key the service stores on registration
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    Object[] a = inv.getArguments();
                    for (Object o : a) {
                        if (o instanceof String s && s.startsWith("enc:v1:")) storedCiphertext = s;
                    }
                    return Boolean.TRUE;
                });
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                any(Object[].class))).thenReturn(List.of(new TravelRulePeerService.PeerView(
                "did:example:vasp1", "VASP One", null, "ACTIVE", NOW)));
        key = service.register("did:example:vasp1", "VASP One", null, java.util.UUID.randomUUID(), "REGISTRY_ADMIN",
                java.util.UUID.randomUUID()).hmacKey();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("vasp_id", "did:example:vasp1", "hmac_key_ciphertext", storedCiphertext)));
    }

    private String ts(long offset) {
        return Long.toString(NOW.getEpochSecond() + offset);
    }

    @Test
    void registrationRequiresSecondApproverAndStoresOnlyCiphertext() {
        assertThat(storedCiphertext).startsWith("enc:v1:").doesNotContain(key);
        assertThatThrownBy(() -> service.register("did:example:v2", null, null, null, "REGISTRY_ADMIN", null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validSignatureAuthenticatesTheRegisteredPeerId() {
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<Object>any(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(1);
        String t = ts(0);
        String sig = TravelRulePeerService.sign(key, t, "did:example:vasp1", body);

        assertThat(service.authenticate("DID:EXAMPLE:VASP1", t, sig, body)).isEqualTo("did:example:vasp1");
    }

    @Test
    void signatureOfAnotherPeerKeyOrAlteredBodyIsRejected() {
        String t = ts(0);
        String forged = TravelRulePeerService.sign("some-other-peer-key", t, "did:example:vasp1", body);
        assertThatThrownBy(() -> service.authenticate("did:example:vasp1", t, forged, body))
                .isInstanceOf(AccessDeniedException.class);
        String good = TravelRulePeerService.sign(key, t, "did:example:vasp1", body);
        assertThatThrownBy(() -> service.authenticate("did:example:vasp1", t, good,
                "{\"x\":2}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staleTimestampIsRejected() {
        String t = ts(-3600);
        String sig = TravelRulePeerService.sign(key, t, "did:example:vasp1", body);
        assertThatThrownBy(() -> service.authenticate("did:example:vasp1", t, sig, body))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void replayedSignatureIsRejected() {
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<Object>any(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(0); // ON CONFLICT DO NOTHING -> already seen
        String t = ts(0);
        String sig = TravelRulePeerService.sign(key, t, "did:example:vasp1", body);
        assertThatThrownBy(() -> service.authenticate("did:example:vasp1", t, sig, body))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unknownOrDisabledPeerIsRejected() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        String t = ts(0);
        assertThatThrownBy(() -> service.authenticate("did:example:ghost", t, "00", body))
                .isInstanceOf(AccessDeniedException.class);
    }
}
