package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.asset.api.AssetCouponPayment;
import de.makibytes.registerwerk.asset.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetSlot;
import de.makibytes.registerwerk.asset.api.AssetSlotRepository;
import de.makibytes.registerwerk.asset.api.AssetTokenUnit;
import de.makibytes.registerwerk.asset.api.AssetTokenUnitRepository;
import de.makibytes.registerwerk.asset.api.CouponStatus;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
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
 * All write operations submit on-chain transactions and record them via
 * {@link BlockchainTransactionService}. State mirrors are updated in the off-chain DB.
 */
@Service
@Transactional
public class Erc3525AdminService {

    private static final Logger log = LoggerFactory.getLogger(Erc3525AdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetSlotRepository slotRepository;
    private final AssetTokenUnitRepository tokenUnitRepository;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;
    private final ApplicationEventPublisher eventPublisher;

    public Erc3525AdminService(
            AssetDeploymentRepository deploymentRepository,
            AssetSlotRepository slotRepository,
            AssetTokenUnitRepository tokenUnitRepository,
            AssetCouponPaymentRepository couponPaymentRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService,
            ApplicationEventPublisher eventPublisher) {
        this.deploymentRepository = deploymentRepository;
        this.slotRepository = slotRepository;
        this.tokenUnitRepository = tokenUnitRepository;
        this.couponPaymentRepository = couponPaymentRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
        this.eventPublisher = eventPublisher;
    }

    // ── Slot-level operations ─────────────────────────────────────────────────

    public UUID createSlot(UUID deploymentId, BigInteger slotId, String name,
                           Map<String, Object> metadata, BigInteger supplyCap) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 createSlot={} on deployment={}", slotId, deploymentId);

        if (supplyCap != null && supplyCap.compareTo(BigInteger.ZERO) > 0) {
            submitEvm(dep, new Function("setSlotSupplyCap",
                    Arrays.asList(new Uint256(slotId), new Uint256(supplyCap)),
                    Collections.emptyList()), "setSlotSupplyCap",
                    Map.of("slotId", slotId.toString(), "supplyCap", supplyCap.toString()));
        }

        AssetSlot slot = new AssetSlot();
        slot.setAssetId(dep.getAssetId());
        slot.setSlotId(slotId);
        slot.setName(name);
        slot.setMetadata(metadata);
        slot.setSupplyCap(supplyCap);
        slotRepository.save(slot);

        return UUID.randomUUID(); // placeholder tx tracking id — real impl reads from txService
    }

    public UUID pauseSlot(UUID deploymentId, BigInteger slotId) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 pauseSlot={} on deployment={}", slotId, deploymentId);

        slotRepository.findByAssetIdAndSlotId(dep.getAssetId(), slotId).ifPresent(slot -> {
            slot.setPaused(true);
            slotRepository.save(slot);
        });

        return submitEvm(dep,
                new Function("pauseSlot", Collections.singletonList(new Uint256(slotId)), Collections.emptyList()),
                "pauseSlot", Map.of("slotId", slotId.toString()));
    }

    public UUID unpauseSlot(UUID deploymentId, BigInteger slotId) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 unpauseSlot={} on deployment={}", slotId, deploymentId);

        slotRepository.findByAssetIdAndSlotId(dep.getAssetId(), slotId).ifPresent(slot -> {
            slot.setPaused(false);
            slotRepository.save(slot);
        });

        return submitEvm(dep,
                new Function("unpauseSlot", Collections.singletonList(new Uint256(slotId)), Collections.emptyList()),
                "unpauseSlot", Map.of("slotId", slotId.toString()));
    }

    public UUID setSlotSupplyCap(UUID deploymentId, BigInteger slotId, BigInteger cap) {
        AssetDeployment dep = requireDeployment(deploymentId);

        slotRepository.findByAssetIdAndSlotId(dep.getAssetId(), slotId).ifPresent(slot -> {
            slot.setSupplyCap(cap);
            slotRepository.save(slot);
        });

        return submitEvm(dep,
                new Function("setSlotSupplyCap",
                        Arrays.asList(new Uint256(slotId), new Uint256(cap)), Collections.emptyList()),
                "setSlotSupplyCap", Map.of("slotId", slotId.toString(), "cap", cap.toString()));
    }

    public UUID setSlotMetadataHash(UUID deploymentId, BigInteger slotId, byte[] metadataHash) {
        AssetDeployment dep = requireDeployment(deploymentId);
        return submitEvm(dep,
                new Function("setSlotMetadataHash",
                        Arrays.asList(new Uint256(slotId), new Bytes32(metadataHash)), Collections.emptyList()),
                "setSlotMetadataHash", Map.of("slotId", slotId.toString()));
    }

    public UUID mintIntoSlot(UUID deploymentId, BigInteger slotId, String toAddress, BigInteger value) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 mint slot={} to={} value={} on deployment={}", slotId, toAddress, value, deploymentId);

        return submitEvm(dep,
                new Function("mint",
                        Arrays.asList(new Address(toAddress), new Uint256(slotId), new Uint256(value)),
                        Collections.singletonList(new org.web3j.abi.TypeReference<Uint256>() {})),
                "mint", Map.of("slotId", slotId.toString(), "to", toAddress, "value", value.toString()));
    }

    // ── Token-level operations ────────────────────────────────────────────────

    public UUID freezeToken(UUID deploymentId, BigInteger tokenId, String reason) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 freezeToken={} reason={} on deployment={}", tokenId, reason, deploymentId);

        tokenUnitRepository.findByAssetIdAndTokenId(dep.getAssetId(), tokenId).ifPresent(unit -> {
            unit.setFrozen(true);
            unit.setFreezeReason(reason);
            tokenUnitRepository.save(unit);
        });

        return submitEvm(dep,
                new Function("freezeToken",
                        Arrays.asList(new Uint256(tokenId), new Utf8String(reason)), Collections.emptyList()),
                "freezeToken", Map.of("tokenId", tokenId.toString(), "reason", reason));
    }

    public UUID unfreezeToken(UUID deploymentId, BigInteger tokenId) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 unfreezeToken={} on deployment={}", tokenId, deploymentId);

        tokenUnitRepository.findByAssetIdAndTokenId(dep.getAssetId(), tokenId).ifPresent(unit -> {
            unit.setFrozen(false);
            unit.setFreezeReason(null);
            tokenUnitRepository.save(unit);
        });

        return submitEvm(dep,
                new Function("unfreezeToken",
                        Collections.singletonList(new Uint256(tokenId)), Collections.emptyList()),
                "unfreezeToken", Map.of("tokenId", tokenId.toString()));
    }

    public UUID forcedValueTransfer(UUID deploymentId, BigInteger fromTokenId, BigInteger toTokenId,
                                    BigInteger value, String legalBasis) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 forcedValueTransfer from={} to={} value={} on deployment={}",
                fromTokenId, toTokenId, value, deploymentId);

        return submitEvm(dep,
                new Function("forcedTransferValue",
                        Arrays.asList(new Uint256(fromTokenId), new Uint256(toTokenId),
                                new Uint256(value), new Utf8String(legalBasis)),
                        Collections.emptyList()),
                "forcedTransferValue",
                Map.of("fromTokenId", fromTokenId.toString(), "toTokenId", toTokenId.toString(),
                        "value", value.toString(), "legalBasis", legalBasis));
    }

    public UUID forceBurnValue(UUID deploymentId, BigInteger tokenId, BigInteger value, String legalBasis) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("ERC-3525 forceBurnValue tokenId={} value={} on deployment={}", tokenId, value, deploymentId);

        return submitEvm(dep,
                new Function("forceBurnValue",
                        Arrays.asList(new Uint256(tokenId), new Uint256(value), new Utf8String(legalBasis)),
                        Collections.emptyList()),
                "forceBurnValue",
                Map.of("tokenId", tokenId.toString(), "value", value.toString(), "legalBasis", legalBasis));
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

    private UUID submitEvm(AssetDeployment dep, Function fn, String methodName, Map<String, Object> params) {
        ChainDescriptor descriptor = new ChainDescriptor(dep.getChain(), dep.getNetwork());
        Web3j web3j = clientRegistry.getEvmClient(descriptor);
        Credentials creds = evmContractService.credentials(descriptor);
        String txHash = evmContractService.submit(web3j, creds, dep.getContractAddress(), fn);
        return txService.record(txHash, methodName, dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
    }
}
