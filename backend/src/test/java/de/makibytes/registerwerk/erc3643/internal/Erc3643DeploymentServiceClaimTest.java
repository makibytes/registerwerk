package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.ClaimSigningService;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.chain.api.ExplorerUrlBuilder;
import de.makibytes.registerwerk.erc3643.api.Erc3643ClaimTopicRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.web3j.abi.datatypes.Function;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers only the claim-issuance/revocation confirmation-tracking fix (Part B) —
 * {@link Erc3643DeploymentService#issueKycClaim} / {@link Erc3643DeploymentService#revokeKycClaim}
 * now submit-and-track instead of block-and-discard, and reject a PENDING identity instead of
 * silently no-op'ing. Full T-REX suite deployment is out of scope for this test class.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Erc3643DeploymentService — claim issuance/revocation confirmation tracking")
class Erc3643DeploymentServiceClaimTest {

    @Mock BlockchainClientRegistry clientRegistry;
    @Mock OnchainIdentityRepository identityRepository;
    @Mock OnchainClaimRepository claimRepository;
    @Mock Erc3643SuiteRepository suiteRepository;
    @Mock Erc3643ClaimTopicRepository claimTopicRepository;
    @Mock AssetDeploymentRepository deploymentRepository;
    @Mock AssetLookupPort assetLookupPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ExplorerUrlBuilder explorerUrlBuilder;
    @Mock ChainConfigRepository chainConfigRepository;
    @Mock EvmContractService evmContractService;
    @Mock DurableEvmTransactionGateway evmTransactions;
    @Mock ContractAddressConfig contractAddressConfig;
    @Mock ClaimSigningService claimSigningService;
    @Mock BlockchainTransactionService blockchainTransactionService;
    @Mock EvmSigner signer;

    private Erc3643DeploymentService service;
    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID identityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new Erc3643DeploymentService(clientRegistry, identityRepository, claimRepository,
                suiteRepository, claimTopicRepository, deploymentRepository, assetLookupPort,
                eventPublisher, explorerUrlBuilder, chainConfigRepository, evmContractService,
                evmTransactions, contractAddressConfig, claimSigningService, blockchainTransactionService);
    }

    private OnchainIdentity deployedIdentity() {
        OnchainIdentity identity = new OnchainIdentity();
        identity.setId(identityId);
        identity.setChainConfigId(chainConfigId);
        identity.setIdentityAddress("0xidentity0000000000000000000000000000001");
        return identity;
    }

    private ChainConfig chainConfig() {
        ChainConfig cc = new ChainConfig();
        cc.setIdentifier("ETHEREUM_MAINNET");
        cc.setNetworkType(ChainConfig.NetworkType.MAINNET);
        return cc;
    }

    @Test
    @DisplayName("issueKycClaim rejects a PENDING identity instead of silently skipping")
    void issueKycClaim_rejectsPendingIdentity() {
        OnchainIdentity identity = deployedIdentity();
        identity.setIdentityAddress("0x-PENDING-abc");
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(identity));

        assertThatThrownBy(() -> service.issueKycClaim(identityId, 1L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not yet deployed");
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
        verify(blockchainTransactionService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("issueKycClaim submits via submit() and tracks the tx, returning the hash without waiting for a receipt")
    void issueKycClaim_submitsAndTracks() {
        OnchainIdentity identity = deployedIdentity();
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(identity));
        when(claimSigningService.signClaim(eq(chainConfigId), eq(identity.getIdentityAddress()), eq(1L), any()))
                .thenReturn(new ClaimSigningService.SignedClaim("0x1234",
                        "0x" + "11".repeat(65), "0x1234567890123456789012345678901234567890"));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig()));
        when(evmTransactions.submit(eq(chainConfigId), eq(identity.getIdentityAddress()),
                any(Function.class), any()))
                .thenReturn("0xissuetx");

        String txHash = service.issueKycClaim(identityId, 1L, null);

        assertThat(txHash).isEqualTo("0xissuetx");
        verify(evmTransactions).submit(eq(chainConfigId), eq(identity.getIdentityAddress()),
                any(Function.class), any());
        verify(blockchainTransactionService).record(eq("0xissuetx"), eq("addClaim"), eq(null), eq(null),
                eq("ETHEREUM"), eq("MAINNET"), eq(identity.getIdentityAddress()), any());
    }

    @Test
    @DisplayName("revokeKycClaim rejects a PENDING identity instead of silently skipping")
    void revokeKycClaim_rejectsPendingIdentity() {
        UUID claimId = UUID.randomUUID();
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setOnchainIdentityId(identityId);
        claim.setTopic(1L);
        OnchainIdentity identity = deployedIdentity();
        identity.setIdentityAddress("0x-PENDING-abc");
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(identity));

        assertThatThrownBy(() -> service.revokeKycClaim(claimId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not yet deployed");
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
    }

    @Test
    @DisplayName("revokeKycClaim submits via submit() and tracks the tx, returning the hash without waiting for a receipt")
    void revokeKycClaim_submitsAndTracks() {
        UUID claimId = UUID.randomUUID();
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setOnchainIdentityId(identityId);
        claim.setTopic(1L);
        OnchainIdentity identity = deployedIdentity();
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(identity));
        when(evmContractService.signer(chainConfigId)).thenReturn(signer);
        when(signer.address()).thenReturn("0xissuer0000000000000000000000000000001");
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig()));
        when(evmTransactions.submit(eq(chainConfigId), eq(identity.getIdentityAddress()),
                any(Function.class), any()))
                .thenReturn("0xrevoketx");

        String txHash = service.revokeKycClaim(claimId);

        assertThat(txHash).isEqualTo("0xrevoketx");
        verify(evmTransactions).submit(eq(chainConfigId), eq(identity.getIdentityAddress()),
                any(Function.class), any());
        verify(blockchainTransactionService).record(eq("0xrevoketx"), eq("removeClaim"), eq(null), eq(null),
                eq("ETHEREUM"), eq("MAINNET"), eq(identity.getIdentityAddress()), any());
    }
}
