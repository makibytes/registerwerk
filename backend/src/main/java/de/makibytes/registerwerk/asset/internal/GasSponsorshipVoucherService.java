package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.GasSponsorshipVoucherIssuedEvent;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigService;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicy;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues {@code EwpgPaymaster} vouchers — the only way a customer UserOperation gets sponsored.
 * Everything the contract cannot see is enforced here before signing:
 * <ul>
 *   <li>the deployment's effective policy is active (DB flag — effective immediately, even
 *       before the on-chain {@code setPolicyActive(false)} lands);</li>
 *   <li>the sender is an active member wallet of the caller's legal entity on that chain;</li>
 *   <li>scope (PARK-T2-01 default, option (a)): the sender holds the deployment's asset — an
 *       active, non-nominee-pool register entry of the caller's entity for that wallet — and
 *       every call in the batch targets the deployment's token contract with zero value, and
 *       the account is an existing/7702 account (no factory deployment). First-time subscribers
 *       (no register entry yet) are therefore not sponsored; they pay their own gas;</li>
 *   <li>gas: {@code maxFeePerGas} within the configured cap (signed into the voucher), total
 *       gas within bounds, postOp gas at or above the contract floor;</li>
 *   <li>the policy's {@code monthlyCapEth}, counting every issued voucher at its worst-case
 *       prefund — once per {@code (policy, sender, userOp nonce)}: re-issuing for the same nonce
 *       replaces the earlier voucher instead of adding to the sum — and a per-entity share of
 *       that cap ({@link PaymasterProperties#getEntityMonthlyCapShare()}), so one organisation
 *       cannot exhaust an issuer's budget for every other holder.</li>
 * </ul>
 * The signed digest is {@link PaymasterVoucherDigest} (chain, paymaster, policy, validity
 * window and fee cap bound; signature bytes excluded).
 */
@Service
@Transactional
public class GasSponsorshipVoucherService {

    private static final Logger log = LoggerFactory.getLogger(GasSponsorshipVoucherService.class);

    /**
     * Mirrors {@code EwpgPaymaster.MIN_POST_OP_GAS_LIMIT}. 150,000 since Glamsterdam: the first
     * sponsorship of an org under a policy creates a storage slot in postOp, which costs ~110k gas
     * under EIP-8037/8038 (about 22k before, hence the former 50,000). Below this the postOp runs out
     * of gas and the op's spend is silently not booked.
     */
    static final BigInteger MIN_POST_OP_GAS_LIMIT = BigInteger.valueOf(150_000);

    /** Simple7702Account (viem {@code toSimple7702SmartAccount}) execution entry points. */
    static final String EXECUTE_SELECTOR = selector("execute(address,uint256,bytes)");
    static final String EXECUTE_BATCH_SELECTOR = selector("executeBatch((address,uint256,bytes)[])");

    private static final BigDecimal WEI_PER_ETH = BigDecimal.TEN.pow(18);

    private final GasSponsorshipService gasSponsorshipService;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final ChainConfigService chainConfigService;
    private final OrgMemberWalletRepository memberWalletRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final GasSponsorshipVoucherRepository voucherRepository;
    private final PaymasterProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final PaymasterOnchainReader onchainReader;

    public GasSponsorshipVoucherService(
            GasSponsorshipService gasSponsorshipService,
            AssetDeploymentRepository assetDeploymentRepository,
            ChainConfigService chainConfigService,
            OrgMemberWalletRepository memberWalletRepository,
            AssetHolderRepository assetHolderRepository,
            GasSponsorshipVoucherRepository voucherRepository,
            PaymasterProperties properties,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            PaymasterOnchainReader onchainReader) {
        this.gasSponsorshipService = gasSponsorshipService;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.chainConfigService = chainConfigService;
        this.memberWalletRepository = memberWalletRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.voucherRepository = voucherRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.onchainReader = onchainReader;
    }

    /** A signed voucher; hex strings are 0x-prefixed. */
    public record IssuedVoucher(
            String paymaster, String paymasterData, BigInteger paymasterVerificationGasLimit,
            BigInteger paymasterPostOpGasLimit, long chainId, String policyId, long validUntil,
            long validAfter, BigInteger maxFeePerGasCap, BigInteger maxCostWei) {}

    public IssuedVoucher issueVoucher(UUID entityId, UUID actorId, String actorRole,
                                      UUID deploymentId, PaymasterVoucherDigest.UserOpFields op) {
        if (entityId == null) {
            throw new AccessDeniedException("Gas sponsorship requires a customer entity");
        }
        if (!properties.isVoucherSigningEnabled()) {
            throw unavailable("voucher signing is not configured");
        }
        AssetDeployment deployment = assetDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (deployment.getChainConfigId() == null || deployment.getContractAddress() == null) {
            throw unavailable("the deployment is not on an EVM chain yet");
        }
        GasSponsorshipPolicy policy = gasSponsorshipService.resolveEffectivePolicy(deployment.getId())
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .orElseThrow(() -> unavailable("no active sponsorship policy for this deployment"));

        ChainConfig chain = chainConfigService.getById(deployment.getChainConfigId());
        String paymaster = properties.addressFor(chain.getIdentifier());
        if (paymaster == null || chain.getChainId() == null) {
            throw unavailable("no paymaster is configured for chain " + chain.getIdentifier());
        }

        String sender = op.sender();
        boolean bound = memberWalletRepository.findActiveByLegalEntityId(entityId).stream()
                .anyMatch(w -> sender.equalsIgnoreCase(w.getWalletAddress())
                        && (w.getChainConfigId() == null || w.getChainConfigId().equals(chain.getId())));
        if (!bound) {
            throw new AccessDeniedException("Sender " + sender + " is not an active wallet of your organisation");
        }
        // PARK-T2-01 (a): deployment/issuer policies are consumable only by holders of the asset.
        boolean holder = deployment.getAssetId() != null
                && assetHolderRepository.findActiveByInvestorId(entityId).stream()
                        .anyMatch(h -> deployment.getAssetId().equals(h.getAssetId())
                                && h.getHolderKind() != HolderKind.NOMINEE_POOL
                                && sender.equalsIgnoreCase(h.getWalletAddress()));
        if (!holder) {
            throw new AccessDeniedException("Sender " + sender + " does not hold this asset; sponsorship is for holders only");
        }

        requireExistingOr7702Account(op.initCode());
        requireScope(op.callData(), deployment.getContractAddress());

        BigInteger feeCap = properties.getMaxFeePerGasCapWei();
        if (op.maxFeePerGas().compareTo(feeCap) > 0 || op.maxPriorityFeePerGas().compareTo(op.maxFeePerGas()) > 0) {
            throw new IllegalArgumentException("maxFeePerGas exceeds the sponsorship cap of " + feeCap + " wei");
        }
        if (op.paymasterPostOpGasLimit().compareTo(MIN_POST_OP_GAS_LIMIT) < 0) {
            throw new IllegalArgumentException("paymasterPostOpGasLimit must be at least " + MIN_POST_OP_GAS_LIMIT);
        }
        BigInteger totalGas = op.verificationGasLimit().add(op.callGasLimit())
                .add(op.paymasterVerificationGasLimit()).add(op.paymasterPostOpGasLimit())
                .add(op.preVerificationGas());
        if (totalGas.compareTo(BigInteger.valueOf(properties.getMaxTotalGas())) > 0) {
            throw new IllegalArgumentException("UserOperation gas limits exceed the sponsorship maximum");
        }
        // EntryPoint v0.8 requiredPrefund — exactly what the paymaster reserves on chain.
        BigInteger maxCost = totalGas.multiply(op.maxFeePerGas());

        Instant now = clock.instant();
        String senderKey = sender.toLowerCase(Locale.ROOT);
        // One voucher per (policy, sender, nonce): a re-issue replaces, never adds (V9 unique index).
        Optional<GasSponsorshipVoucher> previous =
                voucherRepository.findByPolicyIdAndSenderAndUserOpNonce(policy.getId(), senderKey, op.nonce());
        enforceMonthlyCap(policy, entityId, maxCost, previous.orElse(null), now);
        previous.ifPresent(p -> {
            voucherRepository.delete(p);
            voucherRepository.flush(); // delete before the insert, or the unique index trips
        });

        long validUntil = now.getEpochSecond() + properties.getVoucherValiditySeconds();
        long validAfter = 0L;
        byte[] policyId = PaymasterVoucherDigest.policyId(policy.getId());
        byte[] digest = PaymasterVoucherDigest.digest(
                op, chain.getChainId(), paymaster, policyId, validUntil, validAfter, feeCap);
        byte[] signature = sign(digest);

        GasSponsorshipVoucher voucher = new GasSponsorshipVoucher();
        voucher.setPolicyId(policy.getId());
        voucher.setAssetDeploymentId(deployment.getId());
        voucher.setEntityId(entityId);
        voucher.setSender(senderKey);
        voucher.setChainId(chain.getChainId());
        voucher.setUserOpNonce(op.nonce());
        voucher.setMaxCostWei(maxCost);
        voucher.setValidUntil(Instant.ofEpochSecond(validUntil));
        voucher.setCreatedAt(now);
        voucher.setCreatedBy(actorId);
        GasSponsorshipVoucher saved = voucherRepository.save(voucher);

        eventPublisher.publishEvent(new GasSponsorshipVoucherIssuedEvent(
                saved.getId(), policy.getId(), deployment.getId(), entityId, voucher.getSender(),
                chain.getChainId(), maxCost, validUntil, actorId, actorRole));
        log.info("Issued gas-sponsorship voucher {} for policy {} sender {} maxCost={} wei",
                saved.getId(), policy.getId(), voucher.getSender(), maxCost);

        ByteArrayOutputStream paymasterData = new ByteArrayOutputStream();
        paymasterData.writeBytes(policyId);
        paymasterData.writeBytes(Numeric.toBytesPadded(BigInteger.valueOf(validUntil), 6));
        paymasterData.writeBytes(Numeric.toBytesPadded(BigInteger.valueOf(validAfter), 6));
        paymasterData.writeBytes(Numeric.toBytesPadded(feeCap, 16));
        paymasterData.writeBytes(signature);

        return new IssuedVoucher(
                paymaster,
                Numeric.toHexString(paymasterData.toByteArray()),
                op.paymasterVerificationGasLimit(),
                op.paymasterPostOpGasLimit(),
                chain.getChainId(),
                Numeric.toHexString(policyId),
                validUntil,
                validAfter,
                feeCap,
                maxCost);
    }

    /** On-chain state of the deployment's effective policy, for the operator panel. */
    public record OnchainStatus(
            OnchainStatusKind status, UUID policyRowId, boolean configured, String paymaster, String chainIdentifier,
            String policyId, boolean registered, boolean active, String funder, String signer, BigInteger balanceWei,
            BigInteger reservedWei, BigInteger orgBudgetCapWei, String error) {}

    /**
     * Why the panel shows what it shows. Every case is a normal answer (HTTP 200), not an error:
     * an unconfigured paymaster or a missing policy is a deployment fact, not a failed request.
     */
    public enum OnchainStatusKind {
        /** No sponsorship policy applies to the deployment. */
        NO_POLICY,
        /** The deployment is not on a (configured) EVM chain yet. */
        NO_CHAIN,
        /** No {@code EwpgPaymaster} address is configured for the deployment's chain. */
        NOT_CONFIGURED,
        /** The paymaster was read; see the state fields. */
        OK,
        /** Reading the paymaster failed; see {@code error}. */
        READ_ERROR
    }

    @Transactional(readOnly = true)
    public OnchainStatus onchainStatus(UUID deploymentId) {
        AssetDeployment deployment = assetDeploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        Optional<GasSponsorshipPolicy> policy = gasSponsorshipService.resolveEffectivePolicy(deploymentId);
        if (policy.isEmpty()) {
            return new OnchainStatus(OnchainStatusKind.NO_POLICY, null, false, null, null,
                    null, false, false, null, null, null, null, null, null);
        }
        UUID rowId = policy.get().getId();
        String policyIdHex = Numeric.toHexString(PaymasterVoucherDigest.policyId(rowId));
        if (deployment.getChainConfigId() == null) {
            return new OnchainStatus(OnchainStatusKind.NO_CHAIN, rowId, false, null, null,
                    policyIdHex, false, false, null, null, null, null, null, null);
        }
        ChainConfig chain = chainConfigService.getById(deployment.getChainConfigId());
        String paymaster = properties.addressFor(chain.getIdentifier());
        if (paymaster == null) {
            return new OnchainStatus(OnchainStatusKind.NOT_CONFIGURED, rowId, false, null, chain.getIdentifier(),
                    policyIdHex, false, false, null, null, null, null, null, null);
        }
        try {
            PaymasterOnchainReader.PolicyState st = onchainReader.read(
                    chain.getIdentifier(), paymaster, PaymasterVoucherDigest.policyId(rowId));
            return new OnchainStatus(OnchainStatusKind.OK, rowId, true, paymaster, chain.getIdentifier(),
                    policyIdHex, st.registered(), st.active(), st.funder(), st.signer(), st.balanceWei(),
                    st.reservedWei(), st.orgBudgetCapWei(), null);
        } catch (RuntimeException e) {
            log.warn("Reading EwpgPaymaster policy {} on {} failed: {}", policyIdHex, chain.getIdentifier(), e.getMessage());
            return new OnchainStatus(OnchainStatusKind.READ_ERROR, rowId, true, paymaster, chain.getIdentifier(),
                    policyIdHex, false, false, null, null, null, null, null, e.getMessage());
        }
    }

    // ── Checks ────────────────────────────────────────────────────────────────

    private void enforceMonthlyCap(GasSponsorshipPolicy policy, UUID entityId, BigInteger maxCost,
                                   GasSponsorshipVoucher replaced, Instant now) {
        BigDecimal capEth = policy.getMonthlyCapEth();
        if (capEth == null) return; // uncapped in the DB; the on-chain org cap still applies
        BigInteger capWei = capEth.multiply(WEI_PER_ETH).toBigInteger();
        Instant monthStart = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                .withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        // The voucher being replaced no longer counts (it is superseded by this one).
        BigInteger replacedCost = replaced != null && !replaced.getCreatedAt().isBefore(monthStart)
                ? replaced.getMaxCostWei() : BigInteger.ZERO;
        BigInteger used = orZero(voucherRepository.sumMaxCostSince(policy.getId(), monthStart)).subtract(replacedCost);
        if (used.add(maxCost).compareTo(capWei) > 0) {
            throw unavailable("the policy's monthly sponsorship cap is reached");
        }
        BigInteger entityCapWei = capEth.multiply(properties.getEntityMonthlyCapShare())
                .multiply(WEI_PER_ETH).toBigInteger();
        BigInteger entityReplaced = replaced != null && entityId.equals(replaced.getEntityId()) ? replacedCost : BigInteger.ZERO;
        BigInteger entityUsed = orZero(voucherRepository.sumMaxCostSinceForEntity(policy.getId(), entityId, monthStart))
                .subtract(entityReplaced);
        if (entityUsed.add(maxCost).compareTo(entityCapWei) > 0) {
            throw unavailable("your organisation's monthly share of this policy's sponsorship cap is reached");
        }
    }

    private static BigInteger orZero(BigInteger v) {
        return v == null ? BigInteger.ZERO : v;
    }

    /** No factory deployment: empty initCode or the bare EIP-7702 marker (optionally zero-padded). */
    static void requireExistingOr7702Account(byte[] initCode) {
        if (initCode.length == 0) return;
        boolean marker = initCode.length >= 2 && initCode[0] == 0x77 && initCode[1] == 0x02 && initCode.length <= 20;
        for (int i = 2; marker && i < initCode.length; i++) {
            if (initCode[i] != 0) marker = false;
        }
        if (!marker) {
            throw new IllegalArgumentException("Sponsored operations cannot deploy or initialise accounts");
        }
    }

    /** Every call of an {@code execute}/{@code executeBatch} must target {@code allowedTarget} with value 0. */
    static void requireScope(byte[] callData, String allowedTarget) {
        List<BigInteger[]> calls = decodeCalls(callData);
        BigInteger allowed = new BigInteger(1, Numeric.hexStringToByteArray(allowedTarget));
        if (calls.isEmpty()) {
            throw new AccessDeniedException("Empty call batch is outside the sponsorship scope");
        }
        for (BigInteger[] call : calls) {
            if (!call[0].equals(allowed) || call[1].signum() != 0) {
                throw new AccessDeniedException("Call target is outside this policy's sponsorship scope");
            }
        }
    }

    /** Returns {target, value} per call. Rejects any other entry point or malformed ABI. */
    static List<BigInteger[]> decodeCalls(byte[] callData) {
        if (callData.length < 4) throw new IllegalArgumentException("callData too short");
        String sel = Numeric.toHexString(callData, 0, 4, true);
        List<BigInteger[]> calls = new ArrayList<>();
        int base = 4;
        if (sel.equals(EXECUTE_SELECTOR)) {
            calls.add(new BigInteger[] {word(callData, base), word(callData, base + 32)});
            word(callData, base + 64); // bytes offset must exist
            return calls;
        }
        if (sel.equals(EXECUTE_BATCH_SELECTOR)) {
            int arrayStart = base + offset(callData, base);
            int n = offset(callData, arrayStart);
            int elems = arrayStart + 32;
            if (n > 64) throw new IllegalArgumentException("Too many calls in batch");
            for (int i = 0; i < n; i++) {
                int elem = elems + offset(callData, elems + 32 * i);
                calls.add(new BigInteger[] {word(callData, elem), word(callData, elem + 32)});
            }
            return calls;
        }
        throw new AccessDeniedException("Only execute/executeBatch calls can be sponsored");
    }

    private static BigInteger word(byte[] data, int pos) {
        if (pos < 0 || pos + 32 > data.length) throw new IllegalArgumentException("Malformed callData");
        byte[] w = new byte[32];
        System.arraycopy(data, pos, w, 0, 32);
        return new BigInteger(1, w);
    }

    private static int offset(byte[] data, int pos) {
        BigInteger v = word(data, pos);
        if (v.bitLength() > 24) throw new IllegalArgumentException("Malformed callData");
        return v.intValueExact();
    }

    // ── Signing ───────────────────────────────────────────────────────────────

    private byte[] sign(byte[] digest) {
        Sign.SignatureData sig = voucherSigner().signPrefixedHash(digest);
        byte[] out = new byte[65];
        System.arraycopy(sig.getR(), 0, out, 0, 32);
        System.arraycopy(sig.getS(), 0, out, 32, 32);
        out[64] = sig.getV()[0];
        return out;
    }

    /** Address of the configured voucher signer (what each on-chain policy must register). */
    @Transactional(readOnly = true)
    public String voucherSignerAddress() {
        return properties.isVoucherSigningEnabled() ? voucherSigner().address() : null;
    }

    /**
     * The voucher key behind the wallet module's {@link EvmSigner} abstraction, so moving it to
     * PKCS#11/KMS later swaps only this factory. Dev mode: the configured software key.
     */
    private EvmSigner voucherSigner() {
        Credentials credentials = Credentials.create(properties.getVoucherSignerKey().trim());
        return new EvmSigner() {
            @Override public String address() { return credentials.getAddress(); }
            @Override public byte[] signTransaction(RawTransaction transaction, long chainId) {
                throw new UnsupportedOperationException("The voucher key never signs transactions");
            }
            @Override public Sign.SignatureData signDigest(byte[] digest) {
                return Sign.signMessage(digest, credentials.getEcKeyPair(), false);
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String selector(String signature) {
        return Numeric.toHexString(Hash.sha3(signature.getBytes(StandardCharsets.US_ASCII)), 0, 4, true);
    }

    private static InvalidStateTransitionException unavailable(String reason) {
        return new InvalidStateTransitionException("Gas sponsorship not available: " + reason);
    }
}
