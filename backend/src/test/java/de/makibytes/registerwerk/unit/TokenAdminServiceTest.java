package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import de.makibytes.registerwerk.blockchain.internal.TokenAdminService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import de.makibytes.registerwerk.wallet.internal.SoftwareEvmSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenAdminService unit tests — rejection paths for new token standards")
class TokenAdminServiceTest {

    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private AssetLookupPort assetLookupPort;
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private EvmContractService evmContractService;
    @Mock private BlockchainTransactionService txService;
    @Mock private de.makibytes.registerwerk.travelrule.api.TravelRuleGate travelRuleGate;
    @Mock private HolderBlockGate holderBlockGate;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ZamaRelayerClient zamaRelayerClient;

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
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erc3525AdminService");
    }

    @Test
    @DisplayName("pause on ERC-4626 deployment routes callers to Erc4626AdminService")
    void pause_rejectsErc4626WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC4626);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erc4626AdminService");
    }

    @Test
    @DisplayName("pause on ERC-7540 deployment routes callers to Erc7540AdminService")
    void pause_rejectsErc7540WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC7540);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erc7540AdminService");
    }

    @Test
    @DisplayName("pause on DAML_BOND_FIXED routes callers to CantonBondOperations")
    void pause_rejectsDamlBondWithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.DAML_BOND_FIXED);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CantonBondOperations");
    }

    @Test
    @DisplayName("pause on SPL_2022_BOND routes callers to SolanaTokenAdminService")
    void pause_rejectsSpl2022BondWithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.SPL_2022_BOND);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SolanaTokenAdminService");
    }

    @Test
    @DisplayName("pause on SPL_2022_CONFIDENTIAL routes callers to SolanaTokenAdminService")
    void pause_rejectsSpl2022ConfidentialWithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.SPL_2022_CONFIDENTIAL);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SolanaTokenAdminService");
    }

    @Test
    @DisplayName("pause on STARKNET_ERC3525 routes callers to Erc3525AdminService")
    void pause_rejectsStarknetErc3525WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.STARKNET_ERC3525);
        assertThatThrownBy(() -> tokenAdminService.pause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
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
        when(evmContractService.signer(org.mockito.ArgumentMatchers.<de.makibytes.registerwerk.chain.api.ChainDescriptor>any())).thenReturn(null);
        when(evmContractService.submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("0x" + "c".repeat(64));
        when(txService.record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(UUID.randomUUID());

        tokenAdminService.setSupplyCap(depId, BigInteger.valueOf(1000), UUID.randomUUID(), "REGISTRY_ADMIN");
    }

    // ── ERC-1155 id-aware forced-transfer/force-burn guards ─────────────────

    @Test
    @DisplayName("forcedTransfer rejects ERC-1155 (would silently target token id 0) and points to forcedTransferSingle")
    void forcedTransfer_rejectsErc1155WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC1155);

        assertThatThrownBy(() -> tokenAdminService.forcedTransfer(
                dep.getId(), "0x" + "1".repeat(40), "0x" + "2".repeat(40), BigInteger.TEN, "Court order",
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forcedTransferSingle");
    }

    @Test
    @DisplayName("forceBurn rejects ERC-1155 (would silently target token id 0) and points to forceBurnSingle")
    void forceBurn_rejectsErc1155WithClearMessage() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC1155);

        assertThatThrownBy(() -> tokenAdminService.forceBurn(
                dep.getId(), "0x" + "1".repeat(40), BigInteger.TEN, "Court order",
                UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forceBurnSingle");
    }

    // ── confidentialForceBurn ────────────────────────────────────────────────

    @Test
    @DisplayName("confidentialForceBurn rejects non-confidential token standards")
    void confidentialForceBurn_rejectsNonConfidentialStandard() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC20);

        assertThatThrownBy(() -> tokenAdminService.confidentialForceBurn(
                dep.getId(), "0x" + "1".repeat(40), BigInteger.TEN, "eWpG §26", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confidentialForceBurn requires a configured Zama relayer sidecar")
    void confidentialForceBurn_requiresConfiguredRelayer() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC20);
        when(zamaRelayerClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> tokenAdminService.confidentialForceBurn(
                dep.getId(), "0x" + "1".repeat(40), BigInteger.TEN, "eWpG §26", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relayer");
    }

    @Test
    @DisplayName("confidentialForceBurn encrypts the amount via the relayer and submits confidentialBurn")
    void confidentialForceBurn_encryptsAndSubmits() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC3643);
        EvmSigner creds = new SoftwareEvmSigner(Credentials.create(ECKeyPair.create(BigInteger.TWO)));

        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class))).thenReturn(creds);
        when(zamaRelayerClient.encryptInput(any(), any(), any())).thenReturn(
                new ZamaRelayerClient.EncryptedInput("0x" + "aa".repeat(32), "0x" + "bb".repeat(10)));
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.submit(any(), any(), any(), any())).thenReturn("0x" + "c".repeat(64));
        when(txService.record(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        UUID txId = tokenAdminService.confidentialForceBurn(
                dep.getId(), "0x" + "1".repeat(40), BigInteger.TEN, "eWpG §26", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(txId).isNotNull();
    }

    // ── confidentialMint ─────────────────────────────────────────────────────

    @Test
    @DisplayName("confidentialMint rejects non-confidential token standards")
    void confidentialMint_rejectsNonConfidentialStandard() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC20);

        assertThatThrownBy(() -> tokenAdminService.confidentialMint(
                dep.getId(), "0x" + "2".repeat(40), BigInteger.TEN, UUID.randomUUID(), "ISSUER"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confidentialMint requires a configured Zama relayer sidecar")
    void confidentialMint_requiresConfiguredRelayer() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC20);
        when(zamaRelayerClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> tokenAdminService.confidentialMint(
                dep.getId(), "0x" + "2".repeat(40), BigInteger.TEN, UUID.randomUUID(), "ISSUER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relayer");
    }

    @Test
    @DisplayName("confidentialMint encrypts the amount via the relayer and submits confidentialMint")
    void confidentialMint_encryptsAndSubmits() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC3643);
        EvmSigner creds = new SoftwareEvmSigner(Credentials.create(ECKeyPair.create(BigInteger.TWO)));

        when(zamaRelayerClient.isConfigured()).thenReturn(true);
        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class))).thenReturn(creds);
        when(zamaRelayerClient.encryptInput(any(), any(), any())).thenReturn(
                new ZamaRelayerClient.EncryptedInput("0x" + "aa".repeat(32), "0x" + "bb".repeat(10)));
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.submit(any(), any(), any(), any())).thenReturn("0x" + "d".repeat(64));
        when(txService.record(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        UUID txId = tokenAdminService.confidentialMint(
                dep.getId(), "0x" + "2".repeat(40), BigInteger.TEN, UUID.randomUUID(), "ISSUER");

        assertThat(txId).isNotNull();
    }

    // ── confidentialAddViewer / confidentialRemoveViewer ─────────────────────

    @Test
    @DisplayName("confidentialAddViewer rejects non-confidential token standards")
    void confidentialAddViewer_rejectsNonConfidentialStandard() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC721);

        assertThatThrownBy(() -> tokenAdminService.confidentialAddViewer(
                dep.getId(), "0x" + "3".repeat(40), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confidentialAddViewer submits an addViewer call for confidential standards")
    void confidentialAddViewer_submits() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC20);
        EvmSigner creds = new SoftwareEvmSigner(Credentials.create(ECKeyPair.create(BigInteger.TWO)));

        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class))).thenReturn(creds);
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.submit(any(), any(), any(), any())).thenReturn("0x" + "e".repeat(64));
        when(txService.record(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        UUID txId = tokenAdminService.confidentialAddViewer(
                dep.getId(), "0x" + "3".repeat(40), UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(txId).isNotNull();
    }

    @Test
    @DisplayName("confidentialRemoveViewer rejects non-confidential token standards")
    void confidentialRemoveViewer_rejectsNonConfidentialStandard() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC1155);

        assertThatThrownBy(() -> tokenAdminService.confidentialRemoveViewer(
                dep.getId(), "0x" + "4".repeat(40), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confidentialRemoveViewer submits a removeViewer call for confidential standards")
    void confidentialRemoveViewer_submits() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC3643);
        EvmSigner creds = new SoftwareEvmSigner(Credentials.create(ECKeyPair.create(BigInteger.TWO)));

        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class))).thenReturn(creds);
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.submit(any(), any(), any(), any())).thenReturn("0x" + "f".repeat(64));
        when(txService.record(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        UUID txId = tokenAdminService.confidentialRemoveViewer(
                dep.getId(), "0x" + "4".repeat(40), UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(txId).isNotNull();
    }

    // ── confidentialPause / confidentialUnpause / confidentialSetAddressFrozen ──

    @Test
    @DisplayName("confidentialPause rejects non-CONF_ERC3643 standards (CONF_ERC20 has no pause concept)")
    void confidentialPause_rejectsNonConfidentialErc3643Standard() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC20);

        assertThatThrownBy(() -> tokenAdminService.confidentialPause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confidentialPause submits a pause call for CONF_ERC3643")
    void confidentialPause_submits() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC3643);
        EvmSigner creds = new SoftwareEvmSigner(Credentials.create(ECKeyPair.create(BigInteger.TWO)));

        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class))).thenReturn(creds);
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.submit(any(), any(), any(), any())).thenReturn("0x" + "1".repeat(64));
        when(txService.record(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        UUID txId = tokenAdminService.confidentialPause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(txId).isNotNull();
    }

    @Test
    @DisplayName("confidentialUnpause rejects non-CONF_ERC3643 standards")
    void confidentialUnpause_rejectsNonConfidentialErc3643Standard() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC20);

        assertThatThrownBy(() -> tokenAdminService.confidentialUnpause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confidentialUnpause submits an unpause call for CONF_ERC3643")
    void confidentialUnpause_submits() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC3643);
        EvmSigner creds = new SoftwareEvmSigner(Credentials.create(ECKeyPair.create(BigInteger.TWO)));

        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class))).thenReturn(creds);
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.submit(any(), any(), any(), any())).thenReturn("0x" + "2".repeat(64));
        when(txService.record(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        UUID txId = tokenAdminService.confidentialUnpause(dep.getId(), UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(txId).isNotNull();
    }

    @Test
    @DisplayName("confidentialSetAddressFrozen rejects non-CONF_ERC3643 standards")
    void confidentialSetAddressFrozen_rejectsNonConfidentialErc3643Standard() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC20);

        assertThatThrownBy(() -> tokenAdminService.confidentialSetAddressFrozen(
                dep.getId(), "0x" + "5".repeat(40), true, UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confidentialSetAddressFrozen(true) submits a freeze call for CONF_ERC3643")
    void confidentialSetAddressFrozen_freezes() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC3643);
        EvmSigner creds = new SoftwareEvmSigner(Credentials.create(ECKeyPair.create(BigInteger.TWO)));

        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class))).thenReturn(creds);
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.submit(any(), any(), any(), any())).thenReturn("0x" + "3".repeat(64));
        when(txService.record(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        UUID txId = tokenAdminService.confidentialSetAddressFrozen(
                dep.getId(), "0x" + "5".repeat(40), true, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(txId).isNotNull();
    }

    @Test
    @DisplayName("confidentialSetAddressFrozen(false) submits an unfreeze call for CONF_ERC3643")
    void confidentialSetAddressFrozen_unfreezes() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.CONF_ERC3643);
        EvmSigner creds = new SoftwareEvmSigner(Credentials.create(ECKeyPair.create(BigInteger.TWO)));

        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class))).thenReturn(creds);
        when(clientRegistry.getEvmClient(any())).thenReturn(null);
        when(evmContractService.submit(any(), any(), any(), any())).thenReturn("0x" + "6".repeat(64));
        when(txService.record(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        UUID txId = tokenAdminService.confidentialSetAddressFrozen(
                dep.getId(), "0x" + "5".repeat(40), false, UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(txId).isNotNull();
    }

    // ── §16 eWpG Sperrvermerk enforcement ────────────────────────────────────

    @Test
    @DisplayName("mint refuses when the recipient wallet has an active holder block")
    void mint_refusesBlockedRecipientWallet() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC20);
        String toAddress = "0x" + "2".repeat(40);
        when(holderBlockGate.isBlocked(null, toAddress)).thenReturn(true);

        assertThatThrownBy(() -> tokenAdminService.mint(
                dep.getId(), toAddress, BigInteger.TEN, UUID.randomUUID(), "ISSUER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sperrvermerk");
    }

    @Test
    @DisplayName("forcedTransfer refuses when the destination wallet has an active holder block")
    void forcedTransfer_refusesBlockedDestinationWallet() {
        UUID assetId = UUID.randomUUID();
        AssetDeployment dep = deploymentFor(assetId, TokenStandard.ERC20);
        String from = "0x" + "1".repeat(40);
        String to = "0x" + "2".repeat(40);
        when(holderBlockGate.isBlocked(null, from)).thenReturn(false);
        when(holderBlockGate.isBlocked(null, to)).thenReturn(true);

        assertThatThrownBy(() -> tokenAdminService.forcedTransfer(
                dep.getId(), from, to, BigInteger.TEN, "Court order", UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sperrvermerk");
    }
}
