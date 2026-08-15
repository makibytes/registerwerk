package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.WhitelistService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.abi.datatypes.Function;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.web3j.protocol.Web3j;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the async submit+poll fix (finding #13, Phase 10) — previously this service used
 * the blocking {@code EvmContractService.send()} (up to 120s) inside an HTTP request handler;
 * this file did not exist before the fix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WhitelistService — fire-and-track submit, not blocking send (finding #13)")
class WhitelistServiceTest {

    @Mock private AssetDeploymentRepository assetDeploymentRepository;
    @Mock private AssetHolderRepository assetHolderRepository;
    @Mock private BlockchainClientRegistry blockchainClientRegistry;
    @Mock private EvmContractService evmContractService;
    @Mock private BlockchainTransactionService txService;
    @Mock private Web3j web3j;
    @Mock private EvmSigner credentials;

    private WhitelistService service;

    private static final UUID DEPLOYMENT_ID = UUID.randomUUID();
    private static final UUID ASSET_ID = UUID.randomUUID();
    private static final String WALLET = "0x" + "a".repeat(40);

    @BeforeEach
    void setUp() {
        service = new WhitelistService(
                assetDeploymentRepository, assetHolderRepository, blockchainClientRegistry,
                evmContractService, txService);
    }

    private AssetDeployment deployment() {
        AssetDeployment dep = new AssetDeployment();
        dep.setId(DEPLOYMENT_ID);
        dep.setAssetId(ASSET_ID);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.TESTNET);
        dep.setContractAddress("0xdeployed");
        return dep;
    }

    @Test
    @DisplayName("whitelist() submits (fire-and-track), never blocks via send(), and records the tx")
    void whitelist_usesSubmitNotSend() {
        AssetDeployment dep = deployment();
        when(assetDeploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        when(blockchainClientRegistry.getEvmClient(any())).thenReturn(web3j);
        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class)))
                .thenReturn(credentials);
        when(evmContractService.submit(eq(web3j), eq(credentials), eq("0xdeployed"), any(Function.class)))
                .thenReturn("0xtxhash");
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), anyString()))
                .thenReturn(Optional.empty());

        service.whitelist(DEPLOYMENT_ID, WALLET);

        verify(evmContractService, never()).send(any(), any(), anyString(), any(Function.class));
        verify(evmContractService).submit(eq(web3j), eq(credentials), eq("0xdeployed"), any(Function.class));
        verify(txService).record(eq("0xtxhash"), eq("whitelist"), eq(DEPLOYMENT_ID), eq(ASSET_ID),
                anyString(), anyString(), eq("0xdeployed"), any());
    }

    @Test
    @DisplayName("whitelist() still marks the active holder row as whitelisted")
    void whitelist_updatesHolderRecord() {
        AssetDeployment dep = deployment();
        when(assetDeploymentRepository.findById(DEPLOYMENT_ID)).thenReturn(Optional.of(dep));
        when(blockchainClientRegistry.getEvmClient(any())).thenReturn(web3j);
        when(evmContractService.signer(any(de.makibytes.registerwerk.chain.api.ChainDescriptor.class)))
                .thenReturn(credentials);
        when(evmContractService.submit(any(), any(), anyString(), any(Function.class))).thenReturn("0xtxhash");

        AssetHolder holder = new AssetHolder();
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(eq(ASSET_ID), anyString()))
                .thenReturn(Optional.of(holder));

        service.whitelist(DEPLOYMENT_ID, WALLET);

        assertThat(holder.getWhitelisted()).isTrue();
        verify(assetHolderRepository).save(holder);
    }
}
