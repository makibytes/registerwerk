package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.finality.api.ChainQuarantinedException;
import de.makibytes.registerwerk.finality.api.ChainSubmissionExecutor;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SolanaTokenAdminServiceTest {

    @Test
    void quarantineFailsBeforeClientLookupOrWalletSigning() {
        UUID deploymentId = UUID.randomUUID();
        UUID chainId = UUID.randomUUID();
        AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
        BlockchainClientRegistry clients = mock(BlockchainClientRegistry.class);
        WalletSigner wallets = mock(WalletSigner.class);
        ChainConfigRepository chains = mock(ChainConfigRepository.class);
        ChainSubmissionExecutor submissions = mock(ChainSubmissionExecutor.class);
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setChainConfigId(chainId);
        deployment.setChain(Chain.SOLANA);
        deployment.setNetwork(Network.TESTNET);
        deployment.setContractAddress("11111111111111111111111111111111");
        when(deployments.findById(deploymentId)).thenReturn(Optional.of(deployment));
        when(submissions.execute(eq(chainId), any())).thenThrow(new ChainQuarantinedException(chainId));
        SolanaTokenAdminService service = new SolanaTokenAdminService(
                deployments, clients, wallets, chains, submissions);

        assertThatThrownBy(() -> service.freezeTokenAccount(
                        deploymentId, "11111111111111111111111111111111").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ChainQuarantinedException.class);

        verify(clients, never()).getSolanaClient(any());
        verify(wallets, never()).solanaAccountForChain(any());
        verify(chains, never()).findById(any());
    }
}
