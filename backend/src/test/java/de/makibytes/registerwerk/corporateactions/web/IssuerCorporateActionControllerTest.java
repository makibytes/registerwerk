package de.makibytes.registerwerk.corporateactions.web;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.internal.CorporateActionService;
import de.makibytes.registerwerk.corporateactions.web.dto.IssuerAttestationRequest;
import de.makibytes.registerwerk.corporateactions.web.dto.ProposeCorporateActionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the issuer self-service corporate-action endpoints — this module's first
 * capability for any non-operator actor. Authorization itself is expressed entirely in
 * {@code @PreAuthorize} SpEL (a runtime bean-name lookup, not testable by a plain unit test that
 * calls controller methods directly — this codebase has no MockMvc/security-integration test
 * convention anywhere; see {@code IssuerCorporateActionController}'s class-level annotation for
 * the actual authorization expression). These tests instead cover request→service delegation and
 * response mapping, matching this codebase's established plain-Java controller-test style (see
 * {@code TravelRuleInboxControllerTest}).
 */
class IssuerCorporateActionControllerTest {

    private final CorporateActionService service = mock(CorporateActionService.class);
    private final IssuerCorporateActionController controller = new IssuerCorporateActionController(service);

    /** A non-JWT principal: {@code SecurityUtils.extractUserId} falls back to parsing
     *  {@code Authentication.getName()} as a UUID, and {@code primaryRole} falls back to the
     *  caller-supplied default since {@code extractRoles} requires a JWT principal. */
    private static Authentication authAs(UUID userId) {
        return new TestingAuthenticationToken(userId.toString(), "n/a");
    }

    @Test
    @DisplayName("listForAsset returns every action for the asset as CorporateActionView (including the issuer's own drafts)")
    void listForAsset_returnsViews() {
        UUID assetId = UUID.randomUUID();
        CorporateAction dividend = new CorporateAction();
        dividend.setAssetId(assetId);
        dividend.setActionType(CorporateAction.ActionType.DIVIDEND);
        dividend.setStatus(CorporateAction.Status.PROPOSED);
        when(service.findByAsset(assetId)).thenReturn(List.of(dividend));

        ResponseEntity<List<de.makibytes.registerwerk.corporateactions.web.dto.CorporateActionView>> response =
                controller.listForAsset(assetId);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).status()).isEqualTo(CorporateAction.Status.PROPOSED);
    }

    @Test
    @DisplayName("propose delegates to CorporateActionService.propose with the real caller as actor")
    void propose_delegatesToService() {
        UUID assetId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ProposeCorporateActionRequest request = mock(ProposeCorporateActionRequest.class);
        CorporateAction proposed = new CorporateAction();
        proposed.setAssetId(assetId);
        proposed.setActionType(CorporateAction.ActionType.DIVIDEND);
        proposed.setStatus(CorporateAction.Status.PROPOSED);
        when(service.propose(eq(assetId), eq(request), eq(actorId), any())).thenReturn(proposed);

        ResponseEntity<de.makibytes.registerwerk.corporateactions.web.dto.CorporateActionView> response =
                controller.propose(assetId, request, authAs(actorId));

        assertThat(response.getBody().status()).isEqualTo(CorporateAction.Status.PROPOSED);
        verify(service).propose(assetId, request, actorId, "ISSUER");
    }

    @Test
    @DisplayName("withdraw delegates to CorporateActionService.withdrawProposal, passing assetId through for ownership scoping")
    void withdraw_delegatesToService() {
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        CorporateAction withdrawn = new CorporateAction();
        withdrawn.setAssetId(assetId);
        withdrawn.setStatus(CorporateAction.Status.CANCELLED);
        when(service.withdrawProposal(assetId, actionId, actorId)).thenReturn(withdrawn);

        ResponseEntity<de.makibytes.registerwerk.corporateactions.web.dto.CorporateActionView> response =
                controller.withdraw(assetId, actionId, authAs(actorId));

        assertThat(response.getBody().status()).isEqualTo(CorporateAction.Status.CANCELLED);
        verify(service).withdrawProposal(assetId, actionId, actorId);
    }

    @Test
    @DisplayName("attestSettlement delegates to CorporateActionService.attestSettlementAsIssuer, passing assetId through for ownership scoping")
    void attestSettlement_delegatesToService() {
        UUID assetId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        IssuerAttestationRequest request = new IssuerAttestationRequest("SEPA-REF-1", true);
        CorporateAction attested = new CorporateAction();
        attested.setAssetId(assetId);
        attested.setStatus(CorporateAction.Status.ANNOUNCED);
        when(service.attestSettlementAsIssuer(eq(assetId), eq(actionId), eq("SEPA-REF-1"), eq(actorId), any()))
                .thenReturn(attested);

        ResponseEntity<de.makibytes.registerwerk.corporateactions.web.dto.CorporateActionView> response =
                controller.attestSettlement(assetId, actionId, request, authAs(actorId));

        assertThat(response.getBody()).isNotNull();
        verify(service).attestSettlementAsIssuer(assetId, actionId, "SEPA-REF-1", actorId, "ISSUER");
    }
}
