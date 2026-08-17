package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.Web3j;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit test for the finding #7 (Phase 9) fail-closed fix: deploying a confidential ERC-20 with
 * no configured viewer must abort rather than silently proceed with a permanently undecryptable
 * register entry.
 */
@ExtendWith(MockitoExtension.class)
class ConfidentialErc20ServiceTest {

    @Mock private BlockchainClientRegistry blockchainClientRegistry;
    @Mock private EvmContractService evmContractService;
    @Mock private ContractAddressConfig contractAddressConfig;
    @Mock private AssetLookupPort assetLookupPort;
    @Mock private Web3j web3j;
    @Mock private EvmSigner evmSigner;

    private final UUID assetId = UUID.randomUUID();
    private final ChainDescriptor chain = new ChainDescriptor(Chain.ETHEREUM, Network.TESTNET);

    @Test
    void deploy_throws_whenNoViewersConfigured() {
        lenient().when(assetLookupPort.findById(assetId)).thenReturn(Optional.of(
                new AssetLookupPort.AssetInfo(assetId, "Test Confidential Asset", "DE000TEST001",
                        TokenStandard.CONF_ERC20, "ETHEREUM", "TESTNET", UUID.randomUUID(), "AN-1", "ACTIVE")));
        lenient().when(contractAddressConfig.requireConfidentialFactory(any())).thenReturn("0x" + "aa".repeat(20));
        lenient().when(blockchainClientRegistry.getEvmClient(any())).thenReturn(web3j);
        lenient().when(evmContractService.signer(any(ChainDescriptor.class))).thenReturn(evmSigner);
        when(contractAddressConfig.confidentialInitialViewers(any())).thenReturn(List.of());

        ConfidentialErc20Service service = new ConfidentialErc20Service(
                blockchainClientRegistry, evmContractService, contractAddressConfig, assetLookupPort);

        assertThatThrownBy(() -> service.deploy(assetId, chain, "0x" + "bb".repeat(20)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause().hasMessageContaining("No confidential viewers configured");
    }
}
