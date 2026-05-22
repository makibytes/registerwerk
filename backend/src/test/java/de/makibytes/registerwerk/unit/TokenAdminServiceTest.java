package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.internal.TokenAdminService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenAdminService unit tests — rejection paths for new token standards")
class TokenAdminServiceTest {

    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private AssetLookupPort assetLookupPort;
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private EvmContractService evmContractService;
    @Mock private BlockchainTransactionService txService;

    @InjectMocks
    private TokenAdminService tokenAdminService;

    private AssetDeployment deploymentFor(UUID assetId, TokenStandard standard) {
        UUID depId = UUID.randomUUID();
        AssetDeployment dep = new AssetDeployment();
        dep.setId(depId);
        dep.setAssetId(assetId);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.TESTNET);
        dep.setContractAddress("0x" + "a".repeat(40));
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);

        when(deploymentRepository.findById(depId)).thenReturn(Optional.of(dep));
        when(assetLookupPort.findById(assetId)).thenReturn(
                Optional.of(new AssetLookupPort.AssetInfo(assetId, "Test Asset", null, standard, null, null, null, "AST-001", null)));
        return dep;
    }

    @Test
    @DisplayName("pause on ERC-3525 deployment routes callers to Erc3525AdminService")
    void pause_rejectsErc3525WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC3525);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erc3525AdminService");
    }

    @Test
    @DisplayName("pause on ERC-4626 deployment routes callers to Erc4626AdminService")
    void pause_rejectsErc4626WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC4626);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erc4626AdminService");
    }

    @Test
    @DisplayName("pause on ERC-7540 deployment routes callers to Erc7540AdminService")
    void pause_rejectsErc7540WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC7540);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erc7540AdminService");
    }

    @Test
    @DisplayName("pause on DAML_BOND_FIXED routes callers to CantonBondOperations")
    void pause_rejectsDamlBondWithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.DAML_BOND_FIXED);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CantonBondOperations");
    }

    @Test
    @DisplayName("pause on SPL_2022_BOND routes callers to SolanaTokenAdminService (Phase 4)")
    void pause_rejectsSpl2022BondWithPhase4Message() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.SPL_2022_BOND);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SolanaTokenAdminService");
    }

    @Test
    @DisplayName("pause on SPL_2022_CONFIDENTIAL routes callers to SolanaTokenAdminService (Phase 4)")
    void pause_rejectsSpl2022ConfidentialWithPhase4Message() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.SPL_2022_CONFIDENTIAL);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SolanaTokenAdminService");
    }

    @Test
    @DisplayName("pause on STARKNET_ERC3525 routes callers to Erc3525AdminService")
    void pause_rejectsStarknetErc3525WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.STARKNET_ERC3525);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erc3525AdminService");
    }

    @Test
    @DisplayName("setSupplyCap works on ERC-20 (the general path still operates correctly)")
    void setSupplyCap_doesNotRejectErc20() {
        UUID assetId = UUID.randomUUID();
        UUID depId = UUID.randomUUID();

        AssetDeployment dep = new AssetDeployment();
        dep.setId(depId);
        dep.setAssetId(assetId);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.TESTNET);
        dep.setContractAddress("0x" + "b".repeat(40));

        when(deploymentRepository.findById(depId)).thenReturn(Optional.of(dep));
        when(assetLookupPort.findById(assetId)).thenReturn(
                Optional.of(new AssetLookupPort.AssetInfo(assetId, "ERC-20 Asset", null, TokenStandard.ERC20, null, null, null, "AST-002", null)));
        when(clientRegistry.getEvmClient(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        when(evmContractService.credentials(org.mockito.ArgumentMatchers.<de.makibytes.registerwerk.chain.api.ChainDescriptor>any())).thenReturn(null);
        when(evmContractService.submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("0x" + "c".repeat(64));
        when(txService.record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(UUID.randomUUID());

        tokenAdminService.setSupplyCap(depId, BigInteger.valueOf(1000));
    }
}
