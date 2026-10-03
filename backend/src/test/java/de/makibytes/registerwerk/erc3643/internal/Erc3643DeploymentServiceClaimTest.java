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

    private static final String CLAIM_ISSUER = "0x00000000000000000000000000000000000000c1";
    private static final String SIGNER = "0x00000000000000000000000000000000000000e0";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubClaimIssuer(boolean signerIsKey) {
        when(contractAddressConfig.requireClaimIssuer("ETHEREUM_MAINNET")).thenReturn(CLAIM_ISSUER);
        when(evmContractService.signer(chainConfigId)).thenReturn(signer);
        when(signer.address()).thenReturn(SIGNER);
        when(evmContractService.call(any(), eq(CLAIM_ISSUER), any(Function.class)))
                .thenReturn((java.util.List) java.util.List.of(new org.web3j.abi.datatypes.Bool(signerIsKey)));
    }

    @Test
    @DisplayName("issueKycClaim submits via submit() and tracks the tx, returning the hash without waiting for a receipt")
    void issueKycClaim_submitsAndTracks() {
        OnchainIdentity identity = deployedIdentity();
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(identity));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig()));
        stubClaimIssuer(true);
        when(claimSigningService.signClaim(eq(chainConfigId), eq(CLAIM_ISSUER), eq(identity.getIdentityAddress()),
                eq(1L), any()))
                .thenReturn(new ClaimSigningService.SignedClaim("0x1234", "0x" + "11".repeat(65), CLAIM_ISSUER));
        when(evmTransactions.submit(eq(chainConfigId), eq(identity.getIdentityAddress()),
                any(Function.class), any()))
                .thenReturn("0xissuetx");

        String txHash = service.issueKycClaim(identityId, 1L, null);

        assertThat(txHash).isEqualTo("0xissuetx");
        org.mockito.ArgumentCaptor<Function> fn = org.mockito.ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(chainConfigId), eq(identity.getIdentityAddress()),
                fn.capture(), any());
        // T2-21: ONCHAINID's _issuer is the ClaimIssuer contract, never the signer EOA.
        assertThat(fn.getValue().getName()).isEqualTo("addClaim");
        assertThat(((org.web3j.abi.datatypes.Address) fn.getValue().getInputParameters().get(2)).getValue())
                .isEqualToIgnoringCase(CLAIM_ISSUER);
        verify(blockchainTransactionService).record(eq("0xissuetx"), eq("addClaim"), eq(null), eq(null),
                eq("ETHEREUM"), eq("MAINNET"), eq(identity.getIdentityAddress()), any());
    }

    @Test
    @DisplayName("T2-21: issueKycClaim fails closed without a configured ClaimIssuer (no doomed addClaim broadcast)")
    void issueKycClaim_failsClosedWithoutClaimIssuer() {
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(deployedIdentity()));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig()));
        when(contractAddressConfig.requireClaimIssuer("ETHEREUM_MAINNET"))
                .thenThrow(new IllegalStateException("ClaimIssuer address not configured"));

        assertThatThrownBy(() -> service.issueKycClaim(identityId, 1L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ClaimIssuer");
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
    }

    @Test
    @DisplayName("T2-21: issueKycClaim refuses when the registry signer holds no key on the ClaimIssuer")
    void issueKycClaim_rejectsClaimIssuerNotManagedBySigner() {
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(deployedIdentity()));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig()));
        stubClaimIssuer(false);

        assertThatThrownBy(() -> service.issueKycClaim(identityId, 1L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLAIM/MANAGEMENT key");
        verify(claimSigningService, never()).signClaim(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
    }

    @Test
    @DisplayName("T2-21: removeClaim targets keccak256(abi.encode(claimIssuerContract, topic))")
    void revokeKycClaim_usesClaimIssuerForClaimId() {
        UUID claimId = UUID.randomUUID();
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setOnchainIdentityId(identityId);
        claim.setTopic(1L);
        claim.setIssuerAddress(CLAIM_ISSUER);
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(deployedIdentity()));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig()));
        when(evmTransactions.submit(eq(chainConfigId), anyString(), any(Function.class), any())).thenReturn("0xr");

        service.revokeKycClaim(claimId);

        org.mockito.ArgumentCaptor<Function> fn = org.mockito.ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(chainConfigId), anyString(), fn.capture(), any());
        byte[] expected = org.web3j.crypto.Hash.sha3(org.web3j.utils.Numeric.hexStringToByteArray(
                org.web3j.abi.FunctionEncoder.encodeConstructor(java.util.List.of(
                        new org.web3j.abi.datatypes.Address(CLAIM_ISSUER),
                        new org.web3j.abi.datatypes.generated.Uint256(1)))));
        assertThat(((org.web3j.abi.datatypes.generated.Bytes32) fn.getValue().getInputParameters().get(0)).getValue())
                .isEqualTo(expected);
        verify(evmContractService, never()).signer(any(UUID.class));
    }

    @Test
    @DisplayName("T2-21: a new T-REX suite trusts the ClaimIssuer contract, not the owner/signer EOA")
    void deploySuiteFunction_trustsClaimIssuer() {
        Function fn = Erc3643DeploymentService.buildDeployEwpgSuiteFunction(
                new byte[32], "salt", SIGNER, CLAIM_ISSUER, "Name", "SYM");
        org.web3j.abi.datatypes.DynamicStruct claimDetails =
                (org.web3j.abi.datatypes.DynamicStruct) fn.getInputParameters().get(3);
        @SuppressWarnings("unchecked")
        java.util.List<org.web3j.abi.datatypes.Address> issuers =
                ((org.web3j.abi.datatypes.DynamicArray<org.web3j.abi.datatypes.Address>)
                        claimDetails.getValue().get(1)).getValue();
        assertThat(issuers).extracting(org.web3j.abi.datatypes.Address::getValue)
                .containsExactly(CLAIM_ISSUER);
    }

    @Test
    @DisplayName("C5: a new T-REX suite is deployed with decimals = 0 (register amounts are raw base units counted as whole units)")
    void deploySuiteFunction_deploysWholeUnitToken() {
        Function fn = Erc3643DeploymentService.buildDeployEwpgSuiteFunction(
                new byte[32], "salt", SIGNER, CLAIM_ISSUER, "Name", "SYM");
        org.web3j.abi.datatypes.DynamicStruct tokenDetails =
                (org.web3j.abi.datatypes.DynamicStruct) fn.getInputParameters().get(2);
        // TokenDetails: (owner, name, symbol, decimals, irs, onchainid, irAgents, tokenAgents, modules, settings)
        org.web3j.abi.datatypes.generated.Uint8 decimals =
                (org.web3j.abi.datatypes.generated.Uint8) tokenDetails.getValue().get(3);
        assertThat(decimals.getValue()).isEqualTo(java.math.BigInteger.ZERO);
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

    // ── T2-19: issuer-level revocation (ClaimIssuer.revokeClaimBySignature) ──────────────

    private static final String ISSUER = "0x1234567890123456789012345678901234567890";
    private static final String SIG = "0x" + "11".repeat(65);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubIssuerCode(String code) throws Exception {
        org.web3j.protocol.Web3j web3j = org.mockito.Mockito.mock(org.web3j.protocol.Web3j.class);
        org.web3j.protocol.core.Request request = org.mockito.Mockito.mock(org.web3j.protocol.core.Request.class);
        org.web3j.protocol.core.methods.response.EthGetCode response =
                new org.web3j.protocol.core.methods.response.EthGetCode();
        response.setResult(code);
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_MAINNET")).thenReturn(web3j);
        org.mockito.Mockito.doReturn(request).when(web3j).ethGetCode(eq(ISSUER), any());
        when(request.send()).thenReturn(response);
    }

    private OnchainClaim signedClaim(UUID claimId) {
        OnchainClaim claim = new OnchainClaim();
        claim.setId(claimId);
        claim.setOnchainIdentityId(identityId);
        claim.setTopic(1L);
        claim.setIssuerAddress(ISSUER);
        claim.setClaimSignature(SIG);
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(deployedIdentity()));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig()));
        return claim;
    }

    @Test
    @DisplayName("revokeClaimAtIssuer submits revokeClaimBySignature(sig) to a ClaimIssuer contract")
    void revokeClaimAtIssuer_submitsRevokeBySignature() throws Exception {
        UUID claimId = UUID.randomUUID();
        signedClaim(claimId);
        stubIssuerCode("0x6080");
        when(evmContractService.call(any(), eq(ISSUER), any(Function.class)))
                .thenReturn(java.util.List.of(new org.web3j.abi.datatypes.Bool(false)));
        when(evmTransactions.submit(eq(chainConfigId), eq(ISSUER), any(Function.class), any()))
                .thenReturn("0xissuerrevoketx");

        String txHash = service.revokeClaimAtIssuer(claimId);

        assertThat(txHash).isEqualTo("0xissuerrevoketx");
        org.mockito.ArgumentCaptor<Function> fn = org.mockito.ArgumentCaptor.forClass(Function.class);
        verify(evmTransactions).submit(eq(chainConfigId), eq(ISSUER), fn.capture(), any());
        assertThat(fn.getValue().getName()).isEqualTo("revokeClaimBySignature");
        assertThat(org.web3j.abi.FunctionEncoder.encode(fn.getValue()))
                .startsWith(org.web3j.crypto.Hash.sha3String("revokeClaimBySignature(bytes)").substring(0, 10));
        verify(blockchainTransactionService).record(eq("0xissuerrevoketx"), eq("revokeClaimBySignature"),
                eq(null), eq(null), eq("ETHEREUM"), eq("MAINNET"), eq(ISSUER), any());
    }

    @Test
    @DisplayName("revokeClaimAtIssuer is idempotent: nothing is submitted once isClaimRevoked(sig) is true")
    void revokeClaimAtIssuer_skipsAlreadyRevoked() throws Exception {
        UUID claimId = UUID.randomUUID();
        signedClaim(claimId);
        stubIssuerCode("0x6080");
        when(evmContractService.call(any(), eq(ISSUER), any(Function.class)))
                .thenReturn(java.util.List.of(new org.web3j.abi.datatypes.Bool(true)));

        assertThat(service.revokeClaimAtIssuer(claimId)).isNull();
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
    }

    @Test
    @DisplayName("revokeClaimAtIssuer is a no-op for an EOA issuer (ONCHAINID cannot hold such a claim)")
    void revokeClaimAtIssuer_skipsEoaIssuer() throws Exception {
        UUID claimId = UUID.randomUUID();
        signedClaim(claimId);
        stubIssuerCode("0x");

        assertThat(service.revokeClaimAtIssuer(claimId)).isNull();
        verify(evmContractService, never()).call(any(), anyString(), any(Function.class));
        verify(evmTransactions, never()).submit(any(UUID.class), anyString(), any(Function.class), any());
    }
}
