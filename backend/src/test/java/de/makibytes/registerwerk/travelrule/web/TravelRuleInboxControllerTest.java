package de.makibytes.registerwerk.travelrule.web;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.internal.TravelRulePeerService;
import de.makibytes.registerwerk.travelrule.internal.TravelRuleProperties;
import de.makibytes.registerwerk.travelrule.internal.TravelRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelRuleInboxControllerTest {

    private final TravelRuleService service = mock(TravelRuleService.class);
    private final TravelRulePeerService peers = mock(TravelRulePeerService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TravelRuleProperties properties = new TravelRuleProperties();
    private final Ivms101.TravelRuleMessage payload = new Ivms101.TravelRuleMessage(
            new Ivms101.OriginatingVasp(new Ivms101.VaspIdentity("did:example:vasp1", "VASP One")),
            List.of(new Ivms101.Originator(null, "0xfrom")), null,
            List.of(new Ivms101.Beneficiary(null, "0xto")),
            new Ivms101.TransferDetails("ref", null, null, null, null));
    private byte[] body;

    @BeforeEach
    void body() {
        body = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    }

    private TravelRuleInboxController controller(String legacyKey, boolean production) {
        return new TravelRuleInboxController(service, peers, properties, mapper, legacyKey, production);
    }

    @Test
    void peerPostingAsAnotherVaspIsRejected() {
        // peer M signs for itself but claims X-Vasp-Id = V: the peer service refuses the claim
        when(peers.authenticate("did:example:vasp1", "1", "sig-of-M", body)).thenThrow(new AccessDeniedException("x"));

        assertThatThrownBy(() -> controller("", false).receive("did:example:vasp1", "1", "sig-of-M", null, body))
                .isInstanceOf(AccessDeniedException.class);
        verify(service, never()).receiveInbound(any(), any());
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        assertThatThrownBy(() -> controller("secret", false).receive("did:example:vasp1", null, null, null, body))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller("secret", false).receive("did:example:vasp1", null, null, "secret", body))
                .as("legacy shared key is off by default").isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void legacySharedKeyOnlyOutsideProductionAndOnlyWhenEnabled() {
        properties.setLegacySharedKey(true);
        assertThat(controller("secret", false).receive("did:example:vasp1", null, null, "secret", body)
                .getStatusCode().value()).isEqualTo(202);
        assertThatThrownBy(() -> controller("secret", true).receive("did:example:vasp1", null, null, "secret", body))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void legacySharedKeyIsRefusedWhenProductionModeComesFromTheDashDProperty() {
        // Wave 5a: the controller used @Value("${REGISTERWERK_PRODUCTION_MODE}"), which a
        // -Dregisterwerk.production-mode=true (property, not env var) never reached
        properties.setLegacySharedKey(true);
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty(de.makibytes.registerwerk.shared.ProductionMode.PROPERTY_NAME, "true");
        var production = new TravelRuleInboxController(service, peers, properties, mapper, "secret", env);
        assertThatThrownBy(() -> production.receive("did:example:vasp1", null, null, "secret", body))
                .isInstanceOf(AccessDeniedException.class);
        var dev = new TravelRuleInboxController(service, peers, properties, mapper, "secret",
                new org.springframework.mock.env.MockEnvironment());
        assertThat(dev.receive("did:example:vasp1", null, null, "secret", body).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void authenticatedPeerIdentityIsWhatIsStored() {
        when(peers.authenticate("DID:EXAMPLE:VASP1", "1", "sig", body)).thenReturn("did:example:vasp1");

        var response = controller("", false).receive("DID:EXAMPLE:VASP1", "1", "sig", null, body);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).receiveInbound(org.mockito.ArgumentMatchers.eq("did:example:vasp1"), any());
    }

    @Test
    void oversizedBodyIsRefused() {
        properties.setInboundMaxBodyBytes(10);
        assertThat(controller("", false).receive("did:example:vasp1", "1", "sig", null, body).getStatusCode().value())
                .isEqualTo(413);
    }
}
