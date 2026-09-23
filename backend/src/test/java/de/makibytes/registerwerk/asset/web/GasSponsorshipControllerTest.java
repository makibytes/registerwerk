package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.GasSponsorshipService;
import de.makibytes.registerwerk.asset.internal.GasSponsorshipVoucherService;
import de.makibytes.registerwerk.asset.internal.GasSponsorshipVoucherService.OnchainStatus;
import de.makibytes.registerwerk.asset.internal.GasSponsorshipVoucherService.OnchainStatusKind;
import de.makibytes.registerwerk.asset.web.dto.GasSponsorshipOnchainStatusResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Review phase 2 veto N1: the operator gas panel's on-chain read must not surface as a failed
 * same-origin request (404) when the chain simply has no paymaster configured.
 */
@DisplayName("GasSponsorshipController")
class GasSponsorshipControllerTest {

    @Test
    @DisplayName("GET …/gas-sponsorship/onchain answers 200 with NOT_CONFIGURED when no paymaster is set")
    void onchainStatusNotConfiguredIs200() {
        GasSponsorshipVoucherService voucherService = mock(GasSponsorshipVoucherService.class);
        UUID depId = UUID.randomUUID();
        UUID policyRow = UUID.randomUUID();
        when(voucherService.onchainStatus(depId)).thenReturn(new OnchainStatus(OnchainStatusKind.NOT_CONFIGURED,
                policyRow, false, null, "ETHEREUM_TESTNET", "0x01", false, false, null, null, null, null, null, null));
        GasSponsorshipController controller = new GasSponsorshipController(mock(GasSponsorshipService.class), voucherService);

        ResponseEntity<GasSponsorshipOnchainStatusResponse> response = controller.getOnchainStatus(UUID.randomUUID(), depId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(response.getBody().configured()).isFalse();
        assertThat(response.getBody().chainIdentifier()).isEqualTo("ETHEREUM_TESTNET");
    }
}
