package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.GasSponsorshipVoucherIssuedEvent;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigService;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicy;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review phase 2, T2-01/T2-02: the backend is the {@code EwpgPaymaster} voucher issuer and must
 * enforce what the contract cannot see — active flag, wallet binding, target scope, gas bounds
 * and the monthly cap — and produce a signature the contract accepts.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GasSponsorshipVoucherService")
class GasSponsorshipVoucherServiceTest {

    static final String SIGNER_KEY = "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
    static final String TOKEN = "0x00000000000000000000000000000000000000a1";
    static final String SENDER = "0x00000000000000000000000000000000000000b2";
    static final String PAYMASTER = "0x00000000000000000000000000000000000000c3";

    @Mock GasSponsorshipService gasSponsorshipService;
    @Mock AssetDeploymentRepository deploymentRepository;
    @Mock ChainConfigService chainConfigService;
    @Mock OrgMemberWalletRepository memberWalletRepository;
    @Mock AssetHolderRepository assetHolderRepository;
    @Mock GasSponsorshipVoucherRepository voucherRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymasterProperties properties;
    GasSponsorshipVoucherService service;

    final UUID entityId = UUID.randomUUID();
    final UUID deploymentId = UUID.randomUUID();
    final UUID chainId = UUID.randomUUID();
    final UUID assetId = UUID.randomUUID();
    AssetHolder holderRow;
    GasSponsorshipPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new PaymasterProperties();
        properties.setVoucherSignerKey(SIGNER_KEY);
        properties.getAddresses().put("ethereum-testnet", PAYMASTER);
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);
        service = new GasSponsorshipVoucherService(gasSponsorshipService, deploymentRepository, chainConfigService,
                memberWalletRepository, assetHolderRepository, voucherRepository, properties, eventPublisher, clock, null);

        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setChainConfigId(chainId);
        deployment.setContractAddress(TOKEN);
        deployment.setAssetId(assetId);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));

        policy = new GasSponsorshipPolicy();
        policy.setId(UUID.randomUUID());
        policy.setSponsor(GasSponsorshipPolicy.Sponsor.ISSUER);
        policy.setMonthlyCapEth(new BigDecimal("0.1"));
        policy.setActive(true);
        when(gasSponsorshipService.resolveEffectivePolicy(deploymentId)).thenReturn(Optional.of(policy));

        ChainConfig chain = new ChainConfig();
        chain.setId(chainId);
        chain.setIdentifier("ETHEREUM_TESTNET");
        chain.setChainId(11155111L);
        when(chainConfigService.getById(chainId)).thenReturn(chain);

        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setWalletAddress(SENDER.toUpperCase().replace("0X", "0x"));
        wallet.setChainConfigId(chainId);
        when(memberWalletRepository.findActiveByLegalEntityId(entityId)).thenReturn(List.of(wallet));

        holderRow = new AssetHolder();
        holderRow.setAssetId(assetId);
        holderRow.setInvestorId(entityId);
        holderRow.setWalletAddress(SENDER);
        when(assetHolderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of(holderRow));

        when(voucherRepository.sumMaxCostSince(any(), any())).thenReturn(null);
        when(voucherRepository.sumMaxCostSinceForEntity(any(), any(), any())).thenReturn(null);
        when(voucherRepository.findByPolicyIdAndSenderAndUserOpNonce(any(), any(), any())).thenReturn(Optional.empty());
        when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    static byte[] execute(String target, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Numeric.hexStringToByteArray(GasSponsorshipVoucherService.EXECUTE_SELECTOR));
        out.writeBytes(word(new BigInteger(target.substring(2), 16)));
        out.writeBytes(word(BigInteger.valueOf(value)));
        out.writeBytes(word(BigInteger.valueOf(0x60)));
        out.writeBytes(word(BigInteger.ZERO));
        return out.toByteArray();
    }

    static byte[] executeBatch(String... targets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Numeric.hexStringToByteArray(GasSponsorshipVoucherService.EXECUTE_BATCH_SELECTOR));
        out.writeBytes(word(BigInteger.valueOf(0x20)));
        out.writeBytes(word(BigInteger.valueOf(targets.length)));
        int elemSize = 4 * 32; // target, value, bytes offset, bytes length (empty bytes)
        for (int i = 0; i < targets.length; i++) {
            out.writeBytes(word(BigInteger.valueOf(32L * targets.length + (long) elemSize * i)));
        }
        for (String t : targets) {
            out.writeBytes(word(new BigInteger(t.substring(2), 16)));
            out.writeBytes(word(BigInteger.ZERO));
            out.writeBytes(word(BigInteger.valueOf(0x60)));
            out.writeBytes(word(BigInteger.ZERO));
        }
        return out.toByteArray();
    }

    static byte[] word(BigInteger v) {
        return Numeric.toBytesPadded(v, 32);
    }

    static PaymasterVoucherDigest.UserOpFields op(byte[] callData, BigInteger maxFee, byte[] initCode) {
        return new PaymasterVoucherDigest.UserOpFields(SENDER, BigInteger.ONE, initCode, callData,
                BigInteger.valueOf(100_000), BigInteger.valueOf(200_000),
                BigInteger.valueOf(150_000), BigInteger.valueOf(80_000), BigInteger.valueOf(50_000),
                BigInteger.valueOf(1_000_000_000L), maxFee);
    }

    static PaymasterVoucherDigest.UserOpFields op(byte[] callData) {
        return op(callData, BigInteger.valueOf(2_000_000_000L), new byte[0]);
    }

    @Test
    @DisplayName("signs a voucher the paymaster can verify: layout, bound fields, recoverable signer")
    void issuesVerifiableVoucher() throws Exception {
        PaymasterVoucherDigest.UserOpFields op = op(execute(TOKEN, 0));
        GasSponsorshipVoucherService.IssuedVoucher v = service.issueVoucher(entityId, UUID.randomUUID(), "INVESTOR", deploymentId, op);

        byte[] data = Numeric.hexStringToByteArray(v.paymasterData());
        assertThat(data).hasSize(32 + 6 + 6 + 16 + 65);
        byte[] policyId = Arrays.copyOfRange(data, 0, 32);
        assertThat(policyId).isEqualTo(PaymasterVoucherDigest.policyId(policy.getId()));
        long validUntil = new BigInteger(1, Arrays.copyOfRange(data, 32, 38)).longValueExact();
        assertThat(validUntil).isEqualTo(Instant.parse("2026-09-15T10:05:00Z").getEpochSecond());
        assertThat(new BigInteger(1, Arrays.copyOfRange(data, 44, 60))).isEqualTo(properties.getMaxFeePerGasCapWei());
        assertThat(v.paymaster()).isEqualTo(PAYMASTER);
        assertThat(v.chainId()).isEqualTo(11155111L);
        // maxCost = (100k + 200k + 150k + 80k + 50k) * 2 gwei — EntryPoint v0.8 requiredPrefund
        assertThat(v.maxCostWei()).isEqualTo(BigInteger.valueOf(580_000L).multiply(BigInteger.valueOf(2_000_000_000L)));

        byte[] digest = PaymasterVoucherDigest.digest(op, 11155111L, PAYMASTER, policyId, validUntil, 0L,
                properties.getMaxFeePerGasCapWei());
        byte[] sig = Arrays.copyOfRange(data, 60, 125);
        Sign.SignatureData sd = new Sign.SignatureData(sig[64], Arrays.copyOfRange(sig, 0, 32), Arrays.copyOfRange(sig, 32, 64));
        String recovered = "0x" + Keys.getAddress(Sign.signedPrefixedMessageToKey(digest, sd));
        assertThat(recovered).isEqualToIgnoringCase(Credentials.create(SIGNER_KEY).getAddress());
        assertThat(service.voucherSignerAddress()).isEqualToIgnoringCase(recovered);

        verify(voucherRepository).save(any(GasSponsorshipVoucher.class));
        verify(eventPublisher).publishEvent(any(GasSponsorshipVoucherIssuedEvent.class));
    }

    @Test
    @DisplayName("accepts an executeBatch whose every call targets the deployment's token")
    void acceptsScopedBatch() {
        service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(executeBatch(TOKEN, TOKEN)));
        verify(voucherRepository).save(any());
    }

    @Test
    @DisplayName("refuses a batch that also calls a foreign contract (PARK-T2-01 default scope)")
    void refusesOutOfScopeTarget() {
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId,
                op(executeBatch(TOKEN, "0x00000000000000000000000000000000000000ee"))))
                .isInstanceOf(AccessDeniedException.class);
        verify(voucherRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses a call that moves native value")
    void refusesValueTransfer() {
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 1))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("refuses a sender that is not an active wallet of the caller's entity")
    void refusesUnboundSender() {
        when(memberWalletRepository.findActiveByLegalEntityId(entityId)).thenReturn(List.of());
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("refuses when the policy is deactivated in the DB — before any on-chain call")
    void refusesInactivePolicy() {
        policy.setActive(false);
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("refuses a gas price above the configured cap")
    void refusesGasPriceAboveCap() {
        PaymasterVoucherDigest.UserOpFields op = op(execute(TOKEN, 0), BigInteger.valueOf(1_000_000_000_000L), new byte[0]);
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a factory initCode but accepts the bare EIP-7702 marker")
    void initCodeRules() {
        byte[] factory = Numeric.hexStringToByteArray("0x00000000000000000000000000000000000000f1abcd");
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId,
                op(execute(TOKEN, 0), BigInteger.valueOf(2_000_000_000L), factory)))
                .isInstanceOf(IllegalArgumentException.class);
        service.issueVoucher(entityId, null, "INVESTOR", deploymentId,
                op(execute(TOKEN, 0), BigInteger.valueOf(2_000_000_000L), Numeric.hexStringToByteArray("0x7702")));
    }

    @Test
    @DisplayName("enforces the DB monthly cap against issued vouchers' worst-case cost")
    void enforcesMonthlyCap() {
        when(voucherRepository.sumMaxCostSince(any(), any())).thenReturn(new BigInteger("99999999999999999")); // ~0.1 ETH
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("monthly");
    }

    @Test
    @DisplayName("fails closed when no voucher key or paymaster is configured")
    void failsClosedWithoutConfiguration() {
        properties.setVoucherSignerKey("");
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(InvalidStateTransitionException.class);
        properties.setVoucherSignerKey(SIGNER_KEY);
        properties.getAddresses().clear();
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("veto B1: refuses a wallet that holds no register entry of the deployment's asset (PARK-T2-01 (a))")
    void refusesNonHolder() {
        AssetHolder other = new AssetHolder();
        other.setAssetId(UUID.randomUUID()); // holds a different asset only
        other.setInvestorId(entityId);
        other.setWalletAddress(SENDER);
        when(assetHolderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of(other));
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not hold");
        verify(voucherRepository, never()).save(any());
    }

    @Test
    @DisplayName("veto B1: a nominee-pool register row does not make the wallet a holder")
    void refusesNomineePoolRow() {
        holderRow.setHolderKind(HolderKind.NOMINEE_POOL);
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("veto B1: re-issuing for the same (policy, sender, nonce) replaces the earlier voucher")
    void reissueForSameNonceReplaces() {
        GasSponsorshipVoucher earlier = new GasSponsorshipVoucher();
        earlier.setPolicyId(policy.getId());
        earlier.setEntityId(entityId);
        earlier.setSender(SENDER);
        earlier.setUserOpNonce(BigInteger.ONE);
        earlier.setCreatedAt(Instant.parse("2026-09-15T09:59:00Z"));
        BigInteger cost = BigInteger.valueOf(580_000L).multiply(BigInteger.valueOf(2_000_000_000L));
        earlier.setMaxCostWei(cost);
        when(voucherRepository.findByPolicyIdAndSenderAndUserOpNonce(eq(policy.getId()), eq(SENDER), eq(BigInteger.ONE)))
                .thenReturn(Optional.of(earlier));
        // The sums already include the earlier voucher, which leaves room for exactly one voucher
        // of this cost under the entity share (0.01 ETH) — booking it twice would exceed it.
        BigInteger entityShareWei = new BigInteger("10000000000000000");
        BigInteger alreadyBooked = entityShareWei.subtract(cost).add(BigInteger.ONE).max(cost);
        when(voucherRepository.sumMaxCostSince(any(), any())).thenReturn(alreadyBooked);
        when(voucherRepository.sumMaxCostSinceForEntity(any(), any(), any())).thenReturn(alreadyBooked);

        service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0)));

        var order = inOrder(voucherRepository);
        order.verify(voucherRepository).delete(earlier);
        order.verify(voucherRepository).flush();
        order.verify(voucherRepository).save(any(GasSponsorshipVoucher.class));
    }

    @Test
    @DisplayName("veto B1: one entity cannot consume more than its monthly share of the policy cap")
    void enforcesEntityShareOfCap() {
        // Policy cap 0.1 ETH has plenty left, but this entity already used its 10 % (0.01 ETH).
        when(voucherRepository.sumMaxCostSince(any(), any())).thenReturn(new BigInteger("10000000000000000"));
        when(voucherRepository.sumMaxCostSinceForEntity(any(), eq(entityId), any())).thenReturn(new BigInteger("10000000000000000"));
        assertThatThrownBy(() -> service.issueVoucher(entityId, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("share");
        verify(voucherRepository, never()).save(any());
    }

    @Test
    @DisplayName("veto N1: on-chain status is a normal answer when no paymaster is configured for the chain")
    void onchainStatusNotConfigured() {
        properties.getAddresses().clear();
        GasSponsorshipVoucherService.OnchainStatus st = service.onchainStatus(deploymentId);
        assertThat(st.status()).isEqualTo(GasSponsorshipVoucherService.OnchainStatusKind.NOT_CONFIGURED);
        assertThat(st.configured()).isFalse();
        assertThat(st.chainIdentifier()).isEqualTo("ETHEREUM_TESTNET");
        assertThat(st.policyRowId()).isEqualTo(policy.getId());
    }

    @Test
    @DisplayName("veto N1: on-chain status reports NO_POLICY instead of an empty result")
    void onchainStatusNoPolicy() {
        when(gasSponsorshipService.resolveEffectivePolicy(deploymentId)).thenReturn(Optional.empty());
        assertThat(service.onchainStatus(deploymentId).status())
                .isEqualTo(GasSponsorshipVoucherService.OnchainStatusKind.NO_POLICY);
    }

    @Test
    @DisplayName("refuses callers without an entity")
    void refusesWithoutEntity() {
        assertThatThrownBy(() -> service.issueVoucher(null, null, "INVESTOR", deploymentId, op(execute(TOKEN, 0))))
                .isInstanceOf(AccessDeniedException.class);
    }
}
