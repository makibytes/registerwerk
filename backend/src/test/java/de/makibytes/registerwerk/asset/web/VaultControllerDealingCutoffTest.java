package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.blockchain.api.Erc4626AdminPort;
import de.makibytes.registerwerk.blockchain.api.Erc7540AdminPort;
import de.makibytes.registerwerk.blockchain.api.VaultDealingState;
import de.makibytes.registerwerk.blockchain.web.dto.SetDealingCutoffRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.idempotency.api.RequiresIdempotencyKey;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("VaultController — dealing cut-off (T1-07)")
class VaultControllerDealingCutoffTest {

    private final Erc7540AdminPort vaultPort = mock(Erc7540AdminPort.class);
    private final AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
    private final AssetVaultStateRepository states = mock(AssetVaultStateRepository.class);
    private final VaultController controller = new VaultController(mock(Erc4626AdminPort.class), vaultPort,
            mock(VaultNavStrikeRepository.class), deployments, states);

    @Test
    @DisplayName("changing the dealing cut-off needs step-up + a second approver, an idempotency key, and is a POST")
    void endpointIsStepUpDualControlAndIdempotent() throws Exception {
        Method m = VaultController.class.getMethod("setDealingCutoff", UUID.class, SetDealingCutoffRequest.class,
                org.springframework.security.core.Authentication.class);

        RequiresStepUp step = m.getAnnotation(RequiresStepUp.class);
        assertThat(step).isNotNull();
        assertThat(step.requireSecondApprover()).isTrue();
        assertThat(step.reason()).isEqualTo("VAULT_DEALING_CUTOFF");
        assertThat(m.isAnnotationPresent(RequiresIdempotencyKey.class)).isTrue();
        assertThat(m.getAnnotation(PostMapping.class).value()).containsExactly("/dealing-cutoff");
    }

    @Test
    @DisplayName("the endpoint hands the validated body and the actor to the port and returns the tx id")
    void delegatesToThePort() {
        UUID depId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        when(vaultPort.setDealingCutoff(depId, 61_200, 86_400L, actor, "REGISTRY_ADMIN")).thenReturn(txId);

        ResponseEntity<TxSubmissionResponse> response = controller.setDealingCutoff(depId,
                new SetDealingCutoffRequest(61_200, 86_400L),
                new TestingAuthenticationToken(actor.toString(), "n/a"));

        assertThat(response.getBody()).isEqualTo(new TxSubmissionResponse(txId));
        verify(vaultPort).setDealingCutoff(eq(depId), eq(61_200), eq(86_400L), eq(actor), eq("REGISTRY_ADMIN"));
    }

    @Test
    @DisplayName("the body is validated: cut-off 0-86399 s, period 1 h to 31 days")
    void bodyIsValidated() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(new SetDealingCutoffRequest(61_200, 86_400L))).isEmpty();
        assertThat(validator.validate(new SetDealingCutoffRequest(86_400, 86_400L))).isNotEmpty();
        assertThat(validator.validate(new SetDealingCutoffRequest(-1, 86_400L))).isNotEmpty();
        assertThat(validator.validate(new SetDealingCutoffRequest(61_200, 60L))).isNotEmpty();
        assertThat(validator.validate(new SetDealingCutoffRequest(61_200, 3_000_000L))).isNotEmpty();
        assertThat(validator.validate(new SetDealingCutoffRequest(null, null))).hasSize(2);
    }

    @Test
    @DisplayName("GET vault-state carries the live dealing state next to the NAV")
    void vaultStateIncludesTheDealingState() {
        UUID depId = UUID.randomUUID();
        AssetDeployment dep = new AssetDeployment();
        dep.setId(depId);
        dep.setAssetId(UUID.randomUUID());
        when(deployments.findById(depId)).thenReturn(Optional.of(dep));
        when(states.findById(dep.getAssetId())).thenReturn(Optional.empty());
        VaultDealingState dealing = new VaultDealingState(true, true, true, 61_200, 86_400L,
                Instant.parse("2027-01-15T17:00:00Z"), Instant.parse("2027-01-14T18:00:00Z"));
        when(vaultPort.dealingState(depId)).thenReturn(dealing);

        assertThat(controller.getVaultState(depId).getBody().dealing()).isEqualTo(dealing);
    }
}
