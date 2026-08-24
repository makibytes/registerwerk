package de.makibytes.registerwerk.travelrule.web;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.internal.TravelRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TravelRuleInboxControllerTest {

    private final TravelRuleService service = mock(TravelRuleService.class);
    private final Ivms101.TravelRuleMessage payload = new Ivms101.TravelRuleMessage(
            new Ivms101.OriginatingVasp(new Ivms101.VaspIdentity("did:example:vasp1", "VASP One")),
            List.of(new Ivms101.Originator(null, "0xfrom")), null,
            List.of(new Ivms101.Beneficiary(null, "0xto")),
            new Ivms101.TransferDetails("ref", null, null, null, null));

    @Test
    void rejectsMissingOrIncorrectCredential() {
        TravelRuleInboxController controller = new TravelRuleInboxController(service, "configured-secret");

        assertThatThrownBy(() -> controller.receive("did:example:vasp1", null, payload))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.receive("did:example:vasp1", "wrong", payload))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void blankConfigurationDisablesInbox() {
        TravelRuleInboxController controller = new TravelRuleInboxController(service, "");

        assertThatThrownBy(() -> controller.receive("did:example:vasp1", "anything", payload))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void acceptsAuthenticatedDelivery() {
        TravelRuleInboxController controller = new TravelRuleInboxController(service, "configured-secret");

        var response = controller.receive("did:example:vasp1", "configured-secret", payload);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).receiveInbound("did:example:vasp1", payload);
    }
}
