package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@code OrgEcosystemTxPoller}'s {@code resolveVerdict} — it used to
 * mark org registrations / member wallets / permission grants / trusted issuers ACTIVE on the
 * first receipt with {@code status == "0x1"}, regardless of the chain's confirmation depth or
 * finality model (unlike {@code BlockchainTransactionService.pollPendingTransactions}, its
 * closest analogue, which this poller was inconsistent with). It now consults the same
 * {@code ChainConfig.FinalityModel}-aware decision ({@code EvmUtils.isFinal}) every other
 * confirmation-gated path in the registry uses.
 */
@ExtendWith(MockitoExtension.class)
class OrgEcosystemTxPollerTest {

    @Mock private OrgRegistrationRepository registrationRepository;
    @Mock private OrgMemberWalletRepository walletRepository;
    @Mock private PermissionGrantRepository grantRepository;
    @Mock private EcosystemTrustedIssuerRepository trustedIssuerRepository;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private BlockchainClientRegistry clientRegistry;
    @Mock private Erc3643Api erc3643Api;
    @Mock private EcosystemOnchainBroadcaster broadcaster;
    @Mock private de.makibytes.registerwerk.finality.api.ChainEffectRecorder chainEffectRecorder;

    private BlockchainTxProperties txProperties;
    private OrgEcosystemTxPoller poller;

    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        txProperties = new BlockchainTxProperties();
        txProperties.setDefaultConfirmations(12);
        EvmFinalityResolver finalityResolver = new EvmFinalityResolver(chainConfigRepository, txProperties);
        poller = new OrgEcosystemTxPoller(registrationRepository, walletRepository, grantRepository,
                trustedIssuerRepository, chainConfigRepository, clientRegistry, erc3643Api,
                broadcaster, finalityResolver, chainEffectRecorder);
    }

    private ChainConfig chain(ChainConfig.FinalityModel model) {
        ChainConfig c = new ChainConfig();
        c.setId(chainConfigId);
        c.setIdentifier("ETHEREUM_MAINNET");
        c.setFinalityModel(model);
        return c;
    }

    private Web3j web3jWithReceipt(TransactionReceipt receipt) throws Exception {
        Web3j web3j = mock(Web3j.class, Answers.RETURNS_DEEP_STUBS);
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_MAINNET")).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt(any()).send().getTransactionReceipt())
                .thenReturn(Optional.of(receipt));
        return web3j;
    }

    private static TransactionReceipt minedReceipt(String status, long blockNumber) {
        TransactionReceipt r = new TransactionReceipt();
        r.setStatus(status);
        r.setBlockNumber(org.web3j.utils.Numeric.encodeQuantity(BigInteger.valueOf(blockNumber)));
        return r;
    }

    private OrgRegistration pendingRegistration(String txHash) {
        OrgRegistration reg = new OrgRegistration();
        org.springframework.test.util.ReflectionTestUtils.setField(reg, "id", UUID.randomUUID());
        reg.setChainConfigId(chainConfigId);
        reg.setOrgAddress("0xorg");
        reg.setStatus(OrgRegistrationStatus.PENDING);
        reg.setRegisteredTx(txHash);
        return reg;
    }

    // ── DEPTH_BASED (default) ─────────────────────────────────────────────────

    @Test
    @DisplayName("DEPTH_BASED: mined but below confirmation depth stays PENDING, never ACTIVE")
    void resolveRegistration_depthBased_belowDepth_staysPending() throws Exception {
        OrgRegistration reg = pendingRegistration("0xtx1");
        when(registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)).thenReturn(List.of(reg));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(105)); // depth=6 < 12

        poller.resolvePending();

        assertThat(reg.getStatus()).isEqualTo(OrgRegistrationStatus.PENDING);
        verify(registrationRepository, never()).save(any());
    }

    @Test
    @DisplayName("DEPTH_BASED: mined at/past confirmation depth marks ACTIVE")
    void resolveRegistration_depthBased_atDepth_marksActive() throws Exception {
        OrgRegistration reg = pendingRegistration("0xtx1");
        when(registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)).thenReturn(List.of(reg));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(111)); // depth=12

        poller.resolvePending();

        assertThat(reg.getStatus()).isEqualTo(OrgRegistrationStatus.ACTIVE);
        verify(registrationRepository).save(reg);
        org.mockito.ArgumentCaptor<de.makibytes.registerwerk.finality.api.ChainEffectDescriptor> captor =
                org.mockito.ArgumentCaptor.forClass(de.makibytes.registerwerk.finality.api.ChainEffectDescriptor.class);
        verify(chainEffectRecorder).record(captor.capture());
        assertThat(captor.getValue().effectType()).isEqualTo("ORG_REGISTRATION_CONFIRMED");
        assertThat(captor.getValue().chainConfigId()).isEqualTo(chainConfigId);
        assertThat(captor.getValue().blockNumber()).isEqualTo(100L);
        assertThat(captor.getValue().entityId()).isEqualTo(reg.getId());
    }

    @Test
    @DisplayName("reverted on-chain marks FAILED regardless of depth")
    void resolveRegistration_reverted_marksFailed() throws Exception {
        OrgRegistration reg = pendingRegistration("0xtx1");
        when(registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)).thenReturn(List.of(reg));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        web3jWithReceipt(minedReceipt("0x0", 100));

        poller.resolvePending();

        assertThat(reg.getStatus()).isEqualTo(OrgRegistrationStatus.FAILED);
        verify(registrationRepository).save(reg);
    }

    // ── TAG_BASED ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TAG_BASED: mined but below the node's finalized tag stays PENDING, "
            + "even past the depth threshold")
    void resolveWallet_tagBased_belowFinalizedTag_staysPending() throws Exception {
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setChainConfigId(chainConfigId);
        wallet.setStatus(MemberWalletStatus.PENDING);
        wallet.setBoundTx("0xtx1");
        when(walletRepository.findByStatus(MemberWalletStatus.PENDING)).thenReturn(List.of(wallet));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.TAG_BASED)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.TAG_BASED)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));

        EthBlock finalizedResponse = mock(EthBlock.class, Answers.RETURNS_DEEP_STUBS);
        when(finalizedResponse.hasError()).thenReturn(false);
        when(finalizedResponse.getBlock().getNumber()).thenReturn(BigInteger.valueOf(90)); // < 100
        when(web3j.ethGetBlockByNumber(org.web3j.protocol.core.DefaultBlockParameterName.FINALIZED, false).send())
                .thenReturn(finalizedResponse);

        poller.resolvePending();

        assertThat(wallet.getStatus()).isEqualTo(MemberWalletStatus.PENDING);
        verify(walletRepository, never()).save(any());
        verify(web3j, never()).ethBlockNumber();
    }

    // ── INSTANT ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("INSTANT: first receipt marks ACTIVE immediately, no depth or tag lookup")
    void resolveGrant_instant_marksActiveImmediately() throws Exception {
        PermissionGrant grant = new PermissionGrant();
        grant.setOrgRegistrationId(UUID.randomUUID());
        grant.setStatus(PermissionGrantStatus.PENDING);
        grant.setGrantedTx("0xtx1");
        when(grantRepository.findByStatus(PermissionGrantStatus.PENDING)).thenReturn(List.of(grant));
        when(grantRepository.findByStatusAndRevokedTxIsNull(PermissionGrantStatus.REVOKED)).thenReturn(List.of());

        OrgRegistration orgReg = new OrgRegistration();
        orgReg.setChainConfigId(chainConfigId);
        when(registrationRepository.findById(grant.getOrgRegistrationId())).thenReturn(Optional.of(orgReg));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));

        poller.resolvePending();

        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.ACTIVE);
        verify(grantRepository).save(grant);
        verify(web3j, never()).ethBlockNumber();
        verify(web3j, never()).ethGetBlockByNumber(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ── unresolved chain / unavailable client ────────────────────────────────

    @Test
    @DisplayName("unknown chainConfigId leaves the row untouched (no NPE, no false ACTIVE)")
    void resolveRegistration_unknownChain_staysPending() {
        OrgRegistration reg = pendingRegistration("0xtx1");
        when(registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)).thenReturn(List.of(reg));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.empty());

        poller.resolvePending();

        assertThat(reg.getStatus()).isEqualTo(OrgRegistrationStatus.PENDING);
        verify(registrationRepository, never()).save(any());
    }
}
