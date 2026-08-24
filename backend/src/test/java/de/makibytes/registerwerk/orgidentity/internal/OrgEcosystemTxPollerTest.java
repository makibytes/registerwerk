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
import de.makibytes.registerwerk.orgidentity.api.RoleRestrictionStatus;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
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
import static org.mockito.Mockito.lenient;

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
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private de.makibytes.registerwerk.finality.api.ChainQuarantinePort chainQuarantine;
    @Mock private PlatformTransactionManager transactionManager;

    private BlockchainTxProperties txProperties;
    private OrgEcosystemTxPoller poller;

    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // resolvePending always scans both the forward and inverse lifecycle queues.  Baseline
        // these overloads so a test focused on one queue remains strict about its own stubs.
        lenient().when(grantRepository.findByStatus(PermissionGrantStatus.PENDING)).thenReturn(List.of());
        lenient().when(trustedIssuerRepository.findByStatus(TrustedIssuerStatus.PENDING)).thenReturn(List.of());
        lenient().when(registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)).thenReturn(List.of());
        lenient().when(registrationRepository.findByStatus(OrgRegistrationStatus.SUSPEND_PENDING)).thenReturn(List.of());
        lenient().when(registrationRepository.findByStatus(OrgRegistrationStatus.REINSTATE_PENDING)).thenReturn(List.of());
        lenient().when(walletRepository.findByStatus(MemberWalletStatus.PENDING)).thenReturn(List.of());
        lenient().when(walletRepository.findByStatus(MemberWalletStatus.REMOVAL_PENDING)).thenReturn(List.of());
        lenient().when(grantRepository.findByRoleRestrictionStatus(RoleRestrictionStatus.CHANGE_PENDING))
                .thenReturn(List.of());
        txProperties = new BlockchainTxProperties();
        txProperties.setDefaultConfirmations(12);
        EvmFinalityResolver finalityResolver = new EvmFinalityResolver(chainConfigRepository, txProperties);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        poller = new OrgEcosystemTxPoller(registrationRepository, walletRepository, grantRepository,
                trustedIssuerRepository, chainConfigRepository, clientRegistry, erc3643Api,
                broadcaster, finalityResolver, chainEffectRecorder, eventPublisher, chainQuarantine,
                transactionManager);
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
        r.setBlockHash("0xblock" + blockNumber);
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
        assertThat(reg.getConfirmedBlockNumber()).isEqualTo(100L);
        assertThat(reg.getConfirmedBlockHash()).isEqualTo("0xblock100");
        verify(registrationRepository).save(reg);
        org.mockito.ArgumentCaptor<de.makibytes.registerwerk.finality.api.ChainEffectDescriptor> captor =
                org.mockito.ArgumentCaptor.forClass(de.makibytes.registerwerk.finality.api.ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(captor.capture());
        assertThat(captor.getValue().effectType()).isEqualTo("ORG_REGISTRATION_CONFIRMED");
        assertThat(captor.getValue().chainConfigId()).isEqualTo(chainConfigId);
        assertThat(captor.getValue().blockNumber()).isEqualTo(100L);
        assertThat(captor.getValue().entityId()).isEqualTo(reg.getId());
    }

    @Test
    @DisplayName("successful receipt without a block hash stays PENDING and records no effect")
    void resolveRegistration_successWithoutBlockHash_staysPending() throws Exception {
        OrgRegistration reg = pendingRegistration("0xtx1");
        when(registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)).thenReturn(List.of(reg));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        TransactionReceipt receipt = minedReceipt("0x1", 100);
        receipt.setBlockHash(null);
        web3jWithReceipt(receipt);

        poller.resolvePending();

        assertThat(reg.getStatus()).isEqualTo(OrgRegistrationStatus.PENDING);
        assertThat(reg.getConfirmedBlockNumber()).isNull();
        assertThat(reg.getConfirmedBlockHash()).isNull();
        verify(registrationRepository, never()).save(any());
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("finalized reverted transaction marks FAILED")
    void resolveRegistration_finalizedRevert_marksFailed() throws Exception {
        OrgRegistration reg = pendingRegistration("0xtx1");
        when(registrationRepository.findByStatus(OrgRegistrationStatus.PENDING)).thenReturn(List.of(reg));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x0", 100));
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(111));

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
        OrgRegistration orgReg = new OrgRegistration();
        orgReg.setChainConfigId(chainConfigId);
        when(registrationRepository.findById(grant.getOrgRegistrationId())).thenReturn(Optional.of(orgReg));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET")).thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x1", 100));

        poller.resolvePending();

        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.ACTIVE);
        assertThat(grant.getGrantedChainConfigId()).isEqualTo(chainConfigId);
        assertThat(grant.getGrantedBlockNumber()).isEqualTo(100L);
        assertThat(grant.getGrantedBlockHash()).isEqualTo("0xblock100");
        verify(grantRepository).save(grant);
        verify(web3j, never()).ethBlockNumber();
        verify(web3j, never()).ethGetBlockByNumber(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("confirmed revocation becomes REVOKED only at finality and journals exact provenance")
    void resolveGrantRevocation_confirmed_recordsEffect() throws Exception {
        UUID grantId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        PermissionGrant grant = new PermissionGrant();
        grant.setId(grantId);
        grant.setOrgRegistrationId(registrationId);
        grant.setStatus(PermissionGrantStatus.REVOCATION_PENDING);
        grant.setRevokedTx("0xrevoke");
        when(grantRepository.findByStatus(PermissionGrantStatus.REVOCATION_PENDING)).thenReturn(List.of(grant));

        OrgRegistration registration = new OrgRegistration();
        registration.setChainConfigId(chainConfigId);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        web3jWithReceipt(minedReceipt("0x1", 200));

        poller.resolvePending();

        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.REVOKED);
        assertThat(grant.getRevokedChainConfigId()).isEqualTo(chainConfigId);
        assertThat(grant.getRevokedBlockNumber()).isEqualTo(200L);
        assertThat(grant.getRevokedBlockHash()).isEqualTo("0xblock200");
        org.mockito.ArgumentCaptor<de.makibytes.registerwerk.finality.api.ChainEffectDescriptor> effect =
                org.mockito.ArgumentCaptor.forClass(
                        de.makibytes.registerwerk.finality.api.ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(effect.capture());
        assertThat(effect.getValue().effectType()).isEqualTo("PERMISSION_REVOKED");
        assertThat(effect.getValue().blockHash()).isEqualTo("0xblock200");
        assertThat(effect.getValue().txHash()).isEqualTo("0xrevoke");
    }

    @Test
    @DisplayName("reverted revoke receipt below finality remains fail-closed REVOCATION_PENDING")
    void resolveGrantRevocation_failedReceiptBelowFinality_staysPending() throws Exception {
        UUID registrationId = UUID.randomUUID();
        PermissionGrant grant = new PermissionGrant();
        grant.setOrgRegistrationId(registrationId);
        grant.setStatus(PermissionGrantStatus.REVOCATION_PENDING);
        grant.setRevokedTx("0xrevoke");
        when(grantRepository.findByStatus(PermissionGrantStatus.REVOCATION_PENDING)).thenReturn(List.of(grant));

        OrgRegistration registration = new OrgRegistration();
        registration.setChainConfigId(chainConfigId);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.DEPTH_BASED)));
        Web3j web3j = web3jWithReceipt(minedReceipt("0x0", 200));
        when(web3j.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(205));

        poller.resolvePending();

        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.REVOCATION_PENDING);
        verify(grantRepository, never()).save(any());
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("finalized reverted revoke receipt becomes fail-closed REVOCATION_FAILED")
    void resolveGrantRevocation_finalizedFailure_marksFailedWithoutEffect() throws Exception {
        UUID registrationId = UUID.randomUUID();
        PermissionGrant grant = new PermissionGrant();
        grant.setOrgRegistrationId(registrationId);
        grant.setStatus(PermissionGrantStatus.REVOCATION_PENDING);
        grant.setRevokedTx("0xrevoke");
        when(grantRepository.findByStatus(PermissionGrantStatus.REVOCATION_PENDING)).thenReturn(List.of(grant));

        OrgRegistration registration = new OrgRegistration();
        registration.setChainConfigId(chainConfigId);
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        web3jWithReceipt(minedReceipt("0x0", 201));

        poller.resolvePending();

        assertThat(grant.getStatus()).isEqualTo(PermissionGrantStatus.REVOCATION_FAILED);
        verify(grantRepository).save(grant);
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("confirmed trusted-issuer removal records exact removal incarnation")
    void resolveIssuerRemoval_confirmed_recordsEffect() throws Exception {
        UUID issuerId = UUID.randomUUID();
        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setId(issuerId);
        issuer.setChainConfigId(chainConfigId);
        issuer.setStatus(TrustedIssuerStatus.REMOVAL_PENDING);
        issuer.setRemovedTx("0xremove");
        when(trustedIssuerRepository.findByStatus(TrustedIssuerStatus.REMOVAL_PENDING))
                .thenReturn(List.of(issuer));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        web3jWithReceipt(minedReceipt("0x1", 202));

        poller.resolvePending();

        assertThat(issuer.getStatus()).isEqualTo(TrustedIssuerStatus.REMOVED);
        assertThat(issuer.getRemovedBlockNumber()).isEqualTo(202L);
        assertThat(issuer.getRemovedBlockHash()).isEqualTo("0xblock202");
        org.mockito.ArgumentCaptor<de.makibytes.registerwerk.finality.api.ChainEffectDescriptor> effect =
                org.mockito.ArgumentCaptor.forClass(
                        de.makibytes.registerwerk.finality.api.ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(effect.capture());
        assertThat(effect.getValue().effectType()).isEqualTo("TRUSTED_ISSUER_REMOVED");
        assertThat(effect.getValue().txHash()).isEqualTo("0xremove");
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

    @Test
    @DisplayName("org suspension becomes terminal only after finality and journals exact receipt")
    void resolveOrgSuspension_finalizedSuccess_recordsExactEffect() throws Exception {
        OrgRegistration registration = new OrgRegistration();
        registration.setId(UUID.randomUUID());
        registration.setChainConfigId(chainConfigId);
        registration.setStatus(OrgRegistrationStatus.SUSPEND_PENDING);
        registration.setStatusTx("0xsuspend");
        when(registrationRepository.findByStatus(OrgRegistrationStatus.SUSPEND_PENDING))
                .thenReturn(List.of(registration));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        web3jWithReceipt(minedReceipt("0x1", 301));

        poller.resolvePending();

        assertThat(registration.getStatus()).isEqualTo(OrgRegistrationStatus.SUSPENDED);
        assertThat(registration.getStatusChainConfigId()).isEqualTo(chainConfigId);
        assertThat(registration.getStatusBlockNumber()).isEqualTo(301L);
        assertThat(registration.getStatusBlockHash()).isEqualTo("0xblock301");
        var effect = org.mockito.ArgumentCaptor.forClass(
                de.makibytes.registerwerk.finality.api.ChainEffectDescriptor.class);
        verify(chainEffectRecorder).recordFinalized(effect.capture());
        assertThat(effect.getValue().effectType()).isEqualTo("ORG_SUSPENSION_CONFIRMED");
        assertThat(effect.getValue().txHash()).isEqualTo("0xsuspend");
    }

    @Test
    @DisplayName("finalized failed suspension remains fail-closed with exact receipt but no reversible effect")
    void resolveOrgSuspension_finalizedFailure_persistsCausality() throws Exception {
        OrgRegistration registration = new OrgRegistration();
        registration.setId(UUID.randomUUID());
        registration.setChainConfigId(chainConfigId);
        registration.setStatus(OrgRegistrationStatus.SUSPEND_PENDING);
        registration.setStatusTx("0xsuspend");
        when(registrationRepository.findByStatus(OrgRegistrationStatus.SUSPEND_PENDING))
                .thenReturn(List.of(registration));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        web3jWithReceipt(minedReceipt("0x0", 302));

        poller.resolvePending();

        assertThat(registration.getStatus()).isEqualTo(OrgRegistrationStatus.SUSPEND_FAILED);
        assertThat(registration.getStatusBlockNumber()).isEqualTo(302L);
        assertThat(registration.getStatusBlockHash()).isEqualTo("0xblock302");
        verify(chainEffectRecorder, never()).recordFinalized(any());
    }

    @Test
    @DisplayName("member removal and role restriction wait for finality and journal their exact incarnation")
    void resolveRemainingTransitions_finalizedSuccess_recordsEffects() throws Exception {
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setId(UUID.randomUUID());
        wallet.setChainConfigId(chainConfigId);
        wallet.setStatus(MemberWalletStatus.REMOVAL_PENDING);
        wallet.setRemovedTx("0xremoveMember");
        when(walletRepository.findByStatus(MemberWalletStatus.REMOVAL_PENDING)).thenReturn(List.of(wallet));

        UUID orgId = UUID.randomUUID();
        PermissionGrant grant = new PermissionGrant();
        grant.setId(UUID.randomUUID());
        grant.setOrgRegistrationId(orgId);
        grant.setStatus(PermissionGrantStatus.ACTIVE);
        grant.setRoleRestrictionStatus(RoleRestrictionStatus.CHANGE_PENDING);
        grant.setRequestedRoleRestricted(true);
        grant.setRoleRestrictionTx("0xrestrict");
        when(grantRepository.findByRoleRestrictionStatus(RoleRestrictionStatus.CHANGE_PENDING))
                .thenReturn(List.of(grant));
        OrgRegistration orgRegistration = new OrgRegistration();
        orgRegistration.setChainConfigId(chainConfigId);
        when(registrationRepository.findById(orgId)).thenReturn(Optional.of(orgRegistration));
        when(chainConfigRepository.findById(chainConfigId))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        when(chainConfigRepository.findByIdentifier("ETHEREUM_MAINNET"))
                .thenReturn(Optional.of(chain(ChainConfig.FinalityModel.INSTANT)));
        Web3j web3j = mock(Web3j.class, Answers.RETURNS_DEEP_STUBS);
        when(clientRegistry.getEvmClientByIdentifier("ETHEREUM_MAINNET")).thenReturn(web3j);
        when(web3j.ethGetTransactionReceipt("0xremoveMember").send().getTransactionReceipt())
                .thenReturn(Optional.of(minedReceipt("0x1", 303)));
        when(web3j.ethGetTransactionReceipt("0xrestrict").send().getTransactionReceipt())
                .thenReturn(Optional.of(minedReceipt("0x1", 304)));

        poller.resolvePending();

        assertThat(wallet.getStatus()).isEqualTo(MemberWalletStatus.REMOVED);
        assertThat(wallet.getRemovedBlockHash()).isEqualTo("0xblock303");
        assertThat(grant.getRoleRestrictionStatus()).isEqualTo(RoleRestrictionStatus.STABLE);
        assertThat(grant.isConfirmedRoleRestricted()).isTrue();
        assertThat(grant.getRequestedRoleRestricted()).isNull();
        var effects = org.mockito.ArgumentCaptor.forClass(
                de.makibytes.registerwerk.finality.api.ChainEffectDescriptor.class);
        verify(chainEffectRecorder, org.mockito.Mockito.times(2)).recordFinalized(effects.capture());
        assertThat(effects.getAllValues()).extracting(
                de.makibytes.registerwerk.finality.api.ChainEffectDescriptor::effectType)
                .containsExactlyInAnyOrder("MEMBER_WALLET_REMOVED", "ROLE_RESTRICTION_CONFIRMED");
    }
}
