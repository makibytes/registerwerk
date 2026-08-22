package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionConfirmationService;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionService;
import de.makibytes.registerwerk.corporateactions.web.dto.CorporateActionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code MeCorporateActionController} — this codebase's plain-Java controller-test
 * style (see {@code TravelRuleInboxControllerTest}); {@code @PreAuthorize} SpEL authorization
 * itself has no test convention anywhere in this codebase (no MockMvc/security-integration tests
 * exist), so these cover request→service delegation and response mapping only.
 */
class MeCorporateActionControllerTest {

    private final CorporateActionService corporateActionService = mock(CorporateActionService.class);
    private final CorporateActionConfirmationService confirmationService = mock(CorporateActionConfirmationService.class);
    private final MeCorporateActionController controller =
            new MeCorporateActionController(corporateActionService, confirmationService);

    private static Authentication authAs(UUID entityId) {
        // MeCorporateActionController's confirmation endpoints resolve the caller via
        // SecurityUtils.extractEntityId, which requires a Jwt principal — a plain
        // TestingAuthenticationToken (non-Jwt) makes extractEntityId return null, which is exactly
        // the "bad request" branch covered by badRequestWhenNoEntityId below. myCorporateActions
        // itself doesn't need the caller's identity (authorization is fully SpEL-based), so this
        // is only exercised there.
        return new TestingAuthenticationToken(entityId != null ? entityId.toString() : "anonymous", "n/a");
    }

    @Test
    @DisplayName("myCorporateActions delegates to CorporateActionService.findByAssetForHolder (the dedicated, "
            + "already-status-filtered query), not a raw findByAsset + Java-side filter")
    void myCorporateActions_delegatesToFindByAssetForHolder() {
        UUID assetId = UUID.randomUUID();
        CorporateAction announced = new CorporateAction();
        announced.setAssetId(assetId);
        announced.setActionType(CorporateAction.ActionType.COUPON);
        announced.setStatus(CorporateAction.Status.ANNOUNCED);
        when(corporateActionService.findByAssetForHolder(assetId)).thenReturn(List.of(announced));

        ResponseEntity<List<CorporateActionView>> response = controller.myCorporateActions(assetId);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).status()).isEqualTo(CorporateAction.Status.ANNOUNCED);
        verify(corporateActionService).findByAssetForHolder(assetId);
    }

    @Test
    @DisplayName("myConfirmation returns 400 when the caller's entity id can't be resolved (no Jwt principal)")
    void myConfirmation_badRequestWhenNoEntityId() {
        ResponseEntity<byte[]> response = controller.myConfirmation(UUID.randomUUID(), authAs(null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("myIso20022Confirmation returns 400 when the caller's entity id can't be resolved (no Jwt principal)")
    void myIso20022Confirmation_badRequestWhenNoEntityId() {
        ResponseEntity<byte[]> response = controller.myIso20022Confirmation(UUID.randomUUID(), authAs(null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
