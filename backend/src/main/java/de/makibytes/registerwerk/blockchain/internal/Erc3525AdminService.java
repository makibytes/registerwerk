package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetSlot;
import de.makibytes.registerwerk.deployment.api.AssetSlotRepository;
import de.makibytes.registerwerk.deployment.api.AssetTokenUnit;
import de.makibytes.registerwerk.deployment.api.AssetTokenUnitRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.blockchain.internal.deploy.StarknetErc3525AdminService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Registry-operator administrative controls for EwpgERC3525 (semi-fungible) contracts.
 *
 * <p>ERC-3525 has two levels of control:
 * <ul>
 *   <li>Slot-level: pause/unpause an entire bond series, set supply cap, anchor metadata hash.</li>
 *   <li>Token-level: freeze/unfreeze a specific holding, force value transfer (§24), force burn (§26).</li>
 * </ul>
 *
 * All write operations submit on-chain transactions, record them via {@link
 * BlockchainTransactionService}, and publish a {@link TokenAdminActionEvent} to the audit trail.
 * State mirrors are updated in the off-chain DB.
 *
 * <p>Deployments on Starknet route to {@link StarknetErc3525AdminService} rather than the EVM
 * {@code submitEvm} path.
 */
@Service
@Transactional
public class Erc3525AdminService implements de.makibytes.registerwerk.blockchain.api.Erc3525AdminPort {

    private static final Logger log = LoggerFactory.getLogger(Erc3525AdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetSlotRepository slotRepository;
    private final AssetTokenUnitRepository tokenUnitRepository;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;
    private final ApplicationEventPublisher eventPublisher;
    private final StarknetErc3525AdminService starknetErc3525AdminService;

    public Erc3525AdminService(
            AssetDeploymentRepository deploymentRepository,
            AssetSlotRepository slotRepository,
            AssetTokenUnitRepository tokenUnitRepository,
            AssetCouponPaymentRepository couponPaymentRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService,
            ApplicationEventPublisher eventPublisher,
            StarknetErc3525AdminService starknetErc3525AdminService) {
        this.deploymentRepository = deploymentRepository;
        this.slotRepository = slotRepository;
        this.tokenUnitRepository = tokenUnitRepository;
        this.couponPaymentRepository = couponPaymentRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
        this.eventPublisher = eventPublisher;
        this.starknetErc3525AdminService = starknetErc3525AdminService;
    }

    // ── Slot-level operations ─────────────────────────────────────────────────

    /**
     * ERC-3525 slots are implicit on-chain (a slot is just the {@code uint256} key a token
     * is minted into — there is no on-chain "create slot" call). Registering one is purely
     * an off-chain registry bookkeeping action, except when a supply cap is given, which
     * does require a real {@code setSlotSupplyCap} transaction. Returns the real tracked
     * transaction id when one was submitted, or {@code null} when the slot was registered
     * off-chain only (nothing to track).
     */
    public UUID createSlot(UUID deploymentId, BigInteger slotId, String name,
                           Map<String, Object> metadata, BigInteger supplyCap, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 createSlot={} on deployment={}", slotId, deploymentId);

        if (isStarknet(dep)) {
            UUID txId = recordStarknetInvoke(dep,
                    starknetErc3525AdminService.createSlot(deploymentId, slotId, name, metadata, supplyCap),
                    "createSlot", Map.of("slotId", slotId.toString()), actorId, actorRole);
            // StarknetErc3525AdminService.createSlot() already persists the AssetSlot row itself.
            return txId;
        }

        UUID txId = null;
        if (supplyCap != null && supplyCap.compareTo(BigInteger.ZERO) > 0) {
            txId = submitEvm(dep, new Function("setSlotSupplyCap",
                    Arrays.asList(new Uint256(slotId), new Uint256(supplyCap)),
                    Collections.emptyList()), "setSlotSupplyCap",
                    Map.of("slotId", slotId.toString(), "supplyCap", supplyCap.toString()), actorId, actorRole);
        }

        AssetSlot slot = new AssetSlot();
        slot.setAssetId(dep.getAssetId());
        slot.setSlotId(slotId);
        slot.setName(name);
        slot.setMetadata(metadata);
        slot.setSupplyCap(supplyCap);
        slotRepository.save(slot);

        return txId;
    }

    public UUID pauseSlot(UUID deploymentId, BigInteger slotId, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 pauseSlot={} on deployment={}", slotId, deploymentId);

        if (isStarknet(dep)) {
            // StarknetErc3525AdminService.pauseSlot() already updates the AssetSlot row itself.
            return recordStarknetInvoke(dep, starknetErc3525AdminService.pauseSlot(deploymentId, slotId),
                    "pauseSlot", Map.of("slotId", slotId.toString()), actorId, actorRole);
        }

        slotRepository.findByAssetIdAndSlotId(dep.getAssetId(), slotId).ifPresent(slot -> {
            slot.setPaused(true);
            slotRepository.save(slot);
        });

        return submitEvm(dep,
                new Function("pauseSlot", Collections.singletonList(new Uint256(slotId)), Collections.emptyList()),
                "pauseSlot", Map.of("slotId", slotId.toString()), actorId, actorRole);
    }

    public UUID unpauseSlot(UUID deploymentId, BigInteger slotId, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 unpauseSlot={} on deployment={}", slotId, deploymentId);

        if (isStarknet(dep)) {
            return recordStarknetInvoke(dep, starknetErc3525AdminService.unpauseSlot(deploymentId, slotId),
                    "unpauseSlot", Map.of("slotId", slotId.toString()), actorId, actorRole);
        }

        slotRepository.findByAssetIdAndSlotId(dep.getAssetId(), slotId).ifPresent(slot -> {
            slot.setPaused(false);
            slotRepository.save(slot);
        });

        return submitEvm(dep,
                new Function("unpauseSlot", Collections.singletonList(new Uint256(slotId)), Collections.emptyList()),
                "unpauseSlot", Map.of("slotId", slotId.toString()), actorId, actorRole);
    }

    public UUID setSlotSupplyCap(UUID deploymentId, BigInteger slotId, BigInteger cap, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvm(dep, "setSlotSupplyCap");

        slotRepository.findByAssetIdAndSlotId(dep.getAssetId(), slotId).ifPresent(slot -> {
            slot.setSupplyCap(cap);
            slotRepository.save(slot);
        });

        return submitEvm(dep,
                new Function("setSlotSupplyCap",
                        Arrays.asList(new Uint256(slotId), new Uint256(cap)), Collections.emptyList()),
                "setSlotSupplyCap", Map.of("slotId", slotId.toString(), "cap", cap.toString()), actorId, actorRole);
    }

    public UUID setSlotMetadataHash(UUID deploymentId, BigInteger slotId, byte[] metadataHash,
                                     UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvm(dep, "setSlotMetadataHash");
        return submitEvm(dep,
                new Function("setSlotMetadataHash",
                        Arrays.asList(new Uint256(slotId), new Bytes32(metadataHash)), Collections.emptyList()),
                "setSlotMetadataHash", Map.of("slotId", slotId.toString()), actorId, actorRole);
    }

    public UUID mintIntoSlot(UUID deploymentId, BigInteger slotId, String toAddress, BigInteger value,
                              UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvm(dep, "mintIntoSlot");
        log.info("ERC-3525 mint slot={} to={} value={} on deployment={}", slotId, toAddress, value, deploymentId);

        return submitEvm(dep,
                new Function("mint",
                        Arrays.asList(new Address(toAddress), new Uint256(slotId), new Uint256(value)),
                        Collections.singletonList(new org.web3j.abi.TypeReference<Uint256>() {})),
                "mint", Map.of("slotId", slotId.toString(), "to", toAddress, "value", value.toString()),
                actorId, actorRole);
    }

    // ── Token-level operations ────────────────────────────────────────────────

    public UUID freezeToken(UUID deploymentId, BigInteger tokenId, String reason, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 freezeToken={} reason={} on deployment={}", tokenId, reason, deploymentId);

        if (isStarknet(dep)) {
            return recordStarknetInvoke(dep, starknetErc3525AdminService.freezeToken(deploymentId, tokenId, reason),
                    "freezeToken", Map.of("tokenId", tokenId.toString(), "reason", reason), actorId, actorRole);
        }

        tokenUnitRepository.findByAssetIdAndTokenId(dep.getAssetId(), tokenId).ifPresent(unit -> {
            unit.setFrozen(true);
            unit.setFreezeReason(reason);
            tokenUnitRepository.save(unit);
        });

        return submitEvm(dep,
                new Function("freezeToken",
                        Arrays.asList(new Uint256(tokenId), new Utf8String(reason)), Collections.emptyList()),
                "freezeToken", Map.of("tokenId", tokenId.toString(), "reason", reason), actorId, actorRole);
    }

    public UUID unfreezeToken(UUID deploymentId, BigInteger tokenId, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 unfreezeToken={} on deployment={}", tokenId, deploymentId);

        if (isStarknet(dep)) {
            return recordStarknetInvoke(dep, starknetErc3525AdminService.unfreezeToken(deploymentId, tokenId),
                    "unfreezeToken", Map.of("tokenId", tokenId.toString()), actorId, actorRole);
        }

        tokenUnitRepository.findByAssetIdAndTokenId(dep.getAssetId(), tokenId).ifPresent(unit -> {
            unit.setFrozen(false);
            unit.setFreezeReason(null);
            tokenUnitRepository.save(unit);
        });

        return submitEvm(dep,
                new Function("unfreezeToken",
                        Collections.singletonList(new Uint256(tokenId)), Collections.emptyList()),
                "unfreezeToken", Map.of("tokenId", tokenId.toString()), actorId, actorRole);
    }

    public UUID forcedValueTransfer(UUID deploymentId, BigInteger fromTokenId, BigInteger toTokenId,
                                    BigInteger value, String legalBasis, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 forcedValueTransfer from={} to={} value={} on deployment={}",
                fromTokenId, toTokenId, value, deploymentId);

        if (isStarknet(dep)) {
            return recordStarknetInvoke(dep,
                    starknetErc3525AdminService.forcedValueTransfer(deploymentId, fromTokenId, toTokenId, value, legalBasis),
                    "forcedTransferValue",
                    Map.of("fromTokenId", fromTokenId.toString(), "toTokenId", toTokenId.toString(),
                            "value", value.toString(), "legalBasis", legalBasis), actorId, actorRole);
        }

        return submitEvm(dep,
                new Function("forcedTransferValue",
                        Arrays.asList(new Uint256(fromTokenId), new Uint256(toTokenId),
                                new Uint256(value), new Utf8String(legalBasis)),
                        Collections.emptyList()),
                "forcedTransferValue",
                Map.of("fromTokenId", fromTokenId.toString(), "toTokenId", toTokenId.toString(),
                        "value", value.toString(), "legalBasis", legalBasis), actorId, actorRole);
    }

    public UUID forceBurnValue(UUID deploymentId, BigInteger tokenId, BigInteger value, String legalBasis,
                                UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        requireEvm(dep, "forceBurnValue");
        log.info("ERC-3525 forceBurnValue tokenId={} value={} on deployment={}", tokenId, value, deploymentId);

        return submitEvm(dep,
                new Function("forceBurnValue",
                        Arrays.asList(new Uint256(tokenId), new Uint256(value), new Utf8String(legalBasis)),
                        Collections.emptyList()),
                "forceBurnValue",
                Map.of("tokenId", tokenId.toString(), "value", value.toString(), "legalBasis", legalBasis),
                actorId, actorRole);
    }

    // ── Coupon payment helper ─────────────────────────────────────────────────

    public void recordCouponPayment(UUID assetId, BigInteger slotId, int periodNo,
                                    LocalDate scheduledDate, LocalDate paidDate,
                                    BigDecimal amountPerUnit, String txRef) {
        couponPaymentRepository.findByAssetIdAndCouponStatus(assetId, CouponStatus.SCHEDULED).stream()
                .filter(p -> p.getPeriodNo() == periodNo && slotId.equals(p.getSlotId()))
                .findFirst()
                .ifPresentOrElse(
                        existing -> {
                            existing.setCouponStatus(CouponStatus.PAID);
                            existing.setPaidDate(paidDate);
                            existing.setTxRef(txRef);
                            couponPaymentRepository.save(existing);
                        },
                        () -> {
                            AssetCouponPayment payment = new AssetCouponPayment();
                            payment.setAssetId(assetId);
                            payment.setSlotId(slotId);
                            payment.setPeriodNo(periodNo);
                            payment.setScheduledDate(scheduledDate);
                            payment.setPaidDate(paidDate);
                            payment.setAmountPerUnit(amountPerUnit);
                            payment.setCouponStatus(CouponStatus.PAID);
                            payment.setTxRef(txRef);
                            couponPaymentRepository.save(payment);
                        }
                );
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private AssetDeployment requireDeployment(UUID deploymentId) {
        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (dep.getContractAddress() == null) {
            throw new IllegalStateException("ERC-3525 contract not yet deployed: deploymentId=" + deploymentId);
        }
        return dep;
    }

    private boolean isStarknet(AssetDeployment dep) {
        return dep.getChain() == Chain.STARKNET;
    }

    /** Throws a clear, specific error for an ERC-3525 admin action that has no Starknet
     *  equivalent yet, rather than letting it fall through to {@link #submitEvm} and fail with
     *  a generic "no EVM client configured" error that doesn't explain why. */
    private void requireEvm(AssetDeployment dep, String methodName) {
        if (isStarknet(dep)) {
            throw new UnsupportedOperationException(
                    methodName + " is not yet implemented for Starknet ERC-3525 deployments");
        }
    }

    /** Blocks on a Starknet invoke's future to get its tx hash, then records it through the same
     *  {@link BlockchainTransactionService} tracking and {@link TokenAdminActionEvent} audit
     *  publish every EVM admin action uses, so callers get uniform behavior regardless of chain. */
    private UUID recordStarknetInvoke(AssetDeployment dep, java.util.concurrent.CompletableFuture<String> future,
                                       String methodName, Map<String, Object> params, UUID actorId, String actorRole) {
        String txHash = future.join();
        eventPublisher.publishEvent(new TokenAdminActionEvent(dep.getId(), methodName, actorId, actorRole, params));
        return txService.record(txHash, methodName, dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
    }

    /** Submits the on-chain transaction and publishes a {@link TokenAdminActionEvent} to the
     *  audit log. */
    private UUID submitEvm(AssetDeployment dep, Function fn, String methodName, Map<String, Object> params,
                            UUID actorId, String actorRole) {
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = clientRegistry.getEvmClient(descriptor);
        Credentials creds = evmContractService.credentials(descriptor);
        String txHash = evmContractService.submit(web3j, creds, dep.getContractAddress(), fn);

        eventPublisher.publishEvent(new TokenAdminActionEvent(dep.getId(), methodName, actorId, actorRole, params));

        return txService.record(txHash, methodName, dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
    }
}
