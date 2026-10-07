package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.lending.web.LendingPositionController;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Lender-side API gate (T2-20 / T5-13)")
class LendingPositionControllerLenderGateTest {

    private final LendingPositionService positions = mock(LendingPositionService.class);
    private final LenderEligibilityService lender = mock(LenderEligibilityService.class);
    private final LendingPositionController controller = new LendingPositionController(positions, lender);
    private final UUID entityId = UUID.randomUUID();

    private JwtAuthenticationToken auth() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .claim("entity_id", entityId.toString()).issuedAt(Instant.now()).build();
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    @DisplayName("supply positions are refused for an entity that fails the lender gate, without touching the chain")
    void supplyPositionsRefusedWhenGateFails() {
        doThrow(new ComplianceGateException("not eligible")).when(lender).requireLender(entityId, "viewing and managing supply positions");
        assertThatThrownBy(() -> controller.supplyPositions(auth())).isInstanceOf(ComplianceGateException.class);
        verify(positions, never()).refreshAndListMySupplyPositions(entityId);
    }

    @Test
    @DisplayName("supply positions are listed for an eligible entity")
    void supplyPositionsListedWhenGatePasses() {
        when(positions.refreshAndListMySupplyPositions(entityId)).thenReturn(List.of());
        assertThat(controller.supplyPositions(auth()).getBody()).isEmpty();
        verify(lender).requireLender(entityId, "viewing and managing supply positions");
    }

    @Test
    @DisplayName("the preflight returns the service status for the caller's entity")
    void preflight() {
        var status = new LenderEligibilityService.Status(true, false, List.of("KYC is not APPROVED"));
        when(lender.status(entityId)).thenReturn(status);
        assertThat(controller.lenderEligibility(auth()).getBody()).isEqualTo(status);
    }
}
