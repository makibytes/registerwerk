package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.RegisterUnitsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wave 0b C5: the register counts raw token base units, every register-unit flow reads them as whole units. A
 * deployment that does not report exactly 0 decimals (or whose decimals are unknown) is refused, fail closed.
 */
@DisplayName("RegisterUnits (C5: whole-unit register guard)")
class RegisterUnitsTest {

    private static AssetDeployment deployment(Integer decimals, AssetDeployment.DeploymentStatus status) {
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setAssetId(UUID.randomUUID());
        d.setDeploymentStatus(status);
        d.setTokenDecimals(decimals);
        return d;
    }

    @Test
    @DisplayName("decimals 0 passes")
    void zeroDecimalsPasses() {
        AssetDeployment d = deployment(0, AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(RegisterUnits.isWholeUnit(d)).isTrue();
        RegisterUnits.requireWholeUnit(d, "coupon computation");
    }

    @Test
    @DisplayName("decimals 18 is refused with an operator-readable 409-class exception")
    void eighteenDecimalsRefused() {
        AssetDeployment d = deployment(18, AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(RegisterUnits.isWholeUnit(d)).isFalse();
        assertThatThrownBy(() -> RegisterUnits.requireWholeUnit(d, "coupon computation"))
                .isInstanceOf(RegisterUnitsException.class)
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("coupon computation")
                .hasMessageContaining("decimals=18")
                .hasMessageContaining(d.getId().toString());
    }

    @Test
    @DisplayName("unknown decimals (null) are refused, never assumed to be 0")
    void unknownDecimalsRefused() {
        AssetDeployment d = deployment(null, AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(RegisterUnits.isWholeUnit(d)).isFalse();
        assertThatThrownBy(() -> RegisterUnits.requireWholeUnit(d, "trading"))
                .isInstanceOf(RegisterUnitsException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("a FAILED deployment never held a token and is ignored; PENDING and CONFIRMED count")
    void failedDeploymentIgnored() {
        AssetDeployment failed = deployment(18, AssetDeployment.DeploymentStatus.FAILED);
        AssetDeployment pending = deployment(18, AssetDeployment.DeploymentStatus.PENDING);
        assertThat(RegisterUnits.refusal(List.of(failed), "x")).isEmpty();
        assertThat(RegisterUnits.refusal(List.of(pending), "x")).isPresent();
    }

    @Test
    @DisplayName("an asset with several deployments is refused when ANY live deployment is not whole-unit")
    void anyNonWholeDeploymentRefusesTheAsset() {
        AssetDeployment whole = deployment(0, AssetDeployment.DeploymentStatus.CONFIRMED);
        AssetDeployment fractional = deployment(6, AssetDeployment.DeploymentStatus.CONFIRMED);
        assertThat(RegisterUnits.refusal(List.of(whole, fractional), "redemption"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("decimals=6"));
    }

    @Test
    @DisplayName("an asset without deployments (off-chain register) has nothing to scale and passes")
    void offchainAssetPasses() {
        assertThat(RegisterUnits.refusal(List.of(), "trading")).isEmpty();
        AssetDeploymentRepository repo = mock(AssetDeploymentRepository.class);
        UUID assetId = UUID.randomUUID();
        when(repo.findByAssetId(assetId)).thenReturn(List.of());
        RegisterUnits.requireWholeUnits(repo, assetId, "trading");
    }

    @Test
    @DisplayName("requireWholeUnits loads the asset's deployments and fails closed on a fractional one")
    void requireWholeUnitsAgainstRepository() {
        AssetDeploymentRepository repo = mock(AssetDeploymentRepository.class);
        UUID assetId = UUID.randomUUID();
        when(repo.findByAssetId(assetId)).thenReturn(
                List.of(deployment(18, AssetDeployment.DeploymentStatus.CONFIRMED)));
        assertThatThrownBy(() -> RegisterUnits.requireWholeUnits(repo, assetId, "subscription settlement"))
                .isInstanceOf(RegisterUnitsException.class)
                .hasMessageContaining("subscription settlement");
    }

    @Test
    @DisplayName("what new deployments get: integer-only and re-deployed register standards are 0; fixed-decimals or underlying-derived standards are not")
    void deployedDecimalsByStandard() {
        for (TokenStandard whole : List.of(TokenStandard.ERC20, TokenStandard.ERC3643, TokenStandard.ERC721,
                TokenStandard.ERC1155, TokenStandard.ERC3525, TokenStandard.STARKNET_ERC3525, TokenStandard.SPL,
                TokenStandard.SPL_2022, TokenStandard.SPL_2022_BOND, TokenStandard.DAML_BOND_FIXED,
                TokenStandard.DAML_BOND_FLOATING, TokenStandard.DAML_BOND_ZERO)) {
            assertThat(RegisterUnits.deployedDecimals(whole)).as(whole.name()).isZero();
        }
        // Cairo ERC-20 returns a hard-coded 18; Stellar assets are fixed at 7; vault shares follow the underlying.
        assertThat(RegisterUnits.deployedDecimals(TokenStandard.STARKNET_ERC20)).isEqualTo(18);
        assertThat(RegisterUnits.deployedDecimals(TokenStandard.STELLAR_ASSET)).isEqualTo(7);
        assertThat(RegisterUnits.deployedDecimals(TokenStandard.ERC4626)).isNull();
        assertThat(RegisterUnits.deployedDecimals(TokenStandard.ERC7540)).isNull();
    }
}
