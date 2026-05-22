package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.internal.SolanaTokenAdminService;
import de.makibytes.registerwerk.blockchain.internal.deploy.SplExtensionSet;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SolanaTokenAdminService unit tests — validation and error paths")
class SolanaTokenAdminServiceTest {

    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private WalletSigner walletSigner;
    @Mock private ChainConfigRepository chainConfigRepository;

    @InjectMocks
    private SolanaTokenAdminService service;

    @Test
    @DisplayName("permanentDelegateTransfer throws synchronously when deployment not found")
    void permanentDelegateTransfer_throwsWhenDeploymentMissing() {
        UUID depId = UUID.randomUUID();
        when(deploymentRepository.findById(depId)).thenReturn(Optional.empty());

        // requireDeployment throws synchronously before the CompletableFuture is created
        assertThatThrownBy(() -> service.permanentDelegateTransfer(
                depId, "from111", "to1111", BigInteger.TEN, 6))
                .isInstanceOf(de.makibytes.registerwerk.shared.EntityNotFoundException.class);
    }

    @Test
    @DisplayName("permanentDelegateBurn throws synchronously when contract address is null")
    void permanentDelegateBurn_throwsWhenContractAddressNull() {
        UUID depId = UUID.randomUUID();
        AssetDeployment dep = new AssetDeployment();
        dep.setId(depId);
        dep.setChain(Chain.SOLANA);
        dep.setNetwork(Network.TESTNET);
        dep.setContractAddress(null); // not yet deployed

        when(deploymentRepository.findById(depId)).thenReturn(Optional.of(dep));

        assertThatThrownBy(() -> service.permanentDelegateBurn(
                depId, "account111", BigInteger.ONE, 6))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPL mint not yet deployed");
    }

    @Test
    @DisplayName("SplExtensionSet enum covers all cases required by dispatcher")
    void splExtensionSet_enumValuesExist() {
        org.assertj.core.api.Assertions.assertThat(SplExtensionSet.values())
                .containsExactlyInAnyOrder(SplExtensionSet.NONE, SplExtensionSet.BOND, SplExtensionSet.CONFIDENTIAL);
    }

    @Test
    @DisplayName("freezeTokenAccount throws synchronously when deployment not found")
    void freezeTokenAccount_throwsWhenDeploymentMissing() {
        UUID depId = UUID.randomUUID();
        when(deploymentRepository.findById(depId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.freezeTokenAccount(depId, "account111"))
                .isInstanceOf(de.makibytes.registerwerk.shared.EntityNotFoundException.class);
    }

    @Test
    @DisplayName("thawTokenAccount throws synchronously when deployment not found")
    void thawTokenAccount_throwsWhenDeploymentMissing() {
        UUID depId = UUID.randomUUID();
        when(deploymentRepository.findById(depId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.thawTokenAccount(depId, "account111"))
                .isInstanceOf(de.makibytes.registerwerk.shared.EntityNotFoundException.class);
    }
}
