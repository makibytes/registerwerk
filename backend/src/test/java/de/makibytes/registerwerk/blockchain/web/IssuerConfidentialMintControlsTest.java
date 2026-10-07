package de.makibytes.registerwerk.blockchain.web;

import de.makibytes.registerwerk.blockchain.internal.TokenAdminService;
import de.makibytes.registerwerk.blockchain.web.dto.MintRequest;
import de.makibytes.registerwerk.deployment.api.ForcedOpTargetGuard;
import de.makibytes.registerwerk.kyc.api.OutboundDestinationGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H13: the issuer's confidential mint carries the same controls as the plain mint - step-up with a
 * second approver (with its own reason, so an approval for one cannot be replayed on the other) and
 * the screened-destination gate - before anything is submitted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("H13 issuer confidential mint controls")
class IssuerConfidentialMintControlsTest {
    @Mock TokenAdminService adminService;
    @Mock ForcedOpTargetGuard targetGuard;
    @Mock OutboundDestinationGate destinationGate;

    IssuerTokenController controller;
    final UUID assetId = UUID.randomUUID();
    final UUID depId = UUID.randomUUID();
    final String to = "0x" + "ab".repeat(20);
    final TestingAuthenticationToken auth =
            new TestingAuthenticationToken(UUID.randomUUID().toString(), "n/a", "ROLE_ISSUER");

    @BeforeEach void setUp() {
        controller = new IssuerTokenController(adminService, targetGuard, destinationGate);
    }

    @Test @DisplayName("POST /mint-confidential requires step-up + second approver with a reason distinct from ISSUER_MINT")
    void requiresSecondApproverWithOwnReason() {
        var method = Arrays.stream(IssuerTokenController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .filter(m -> Arrays.asList(m.getAnnotation(PostMapping.class).value()).contains("/mint-confidential"))
                .findFirst().orElseThrow();
        RequiresStepUp stepUp = method.getAnnotation(RequiresStepUp.class);

        assertThat(stepUp).isNotNull();
        assertThat(stepUp.requireSecondApprover()).isTrue();
        assertThat(stepUp.reason()).isEqualTo("ISSUER_MINT_CONFIDENTIAL").isNotEqualTo("ISSUER_MINT");
    }

    @Test @DisplayName("the destination is screened before the mint is submitted, and the holder name is returned")
    void screensDestinationBeforeSubmitting() {
        UUID txId = UUID.randomUUID();
        when(destinationGate.require(assetId, to, "confidentialMint"))
                .thenReturn(new OutboundDestinationGate.ResolvedDestination(UUID.randomUUID(), UUID.randomUUID(), "Holder AG", to));
        when(adminService.confidentialMint(eq(depId), eq(to), eq(BigInteger.TEN), any(), anyString())).thenReturn(txId);

        var response = controller.mintConfidential(assetId, depId, new MintRequest(to, BigInteger.TEN, null), auth);

        InOrder order = inOrder(destinationGate, adminService);
        order.verify(destinationGate).require(assetId, to, "confidentialMint");
        order.verify(adminService).confidentialMint(eq(depId), eq(to), eq(BigInteger.TEN), any(), anyString());
        assertThat(response.getBody().txId()).isEqualTo(txId);
        assertThat(response.getBody().destinationHolder()).isEqualTo("Holder AG");
    }

    @Test @DisplayName("a destination that fails the gate never reaches the mint")
    void refusedDestinationNeverMints() {
        when(destinationGate.require(assetId, to, "confidentialMint")).thenThrow(new ComplianceGateException("not screened"));

        assertThatThrownBy(() -> controller.mintConfidential(assetId, depId, new MintRequest(to, BigInteger.TEN, null), auth))
                .isInstanceOf(ComplianceGateException.class);
        verify(adminService, never()).confidentialMint(any(), any(), any(), any(), any());
    }
}
