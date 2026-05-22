package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.asset.api.AssetDeployment;
import de.makibytes.registerwerk.asset.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.asset.api.AssetSlot;
import de.makibytes.registerwerk.asset.api.AssetSlotRepository;
import de.makibytes.registerwerk.asset.api.AssetTokenUnit;
import de.makibytes.registerwerk.asset.api.AssetTokenUnitRepository;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Registry-operator administrative controls for the Cairo ERC-3525 (SFT) on Starknet.
 *
 * <p>Slot-level and token-level operations mirror {@link Erc3525AdminService} but use the
 * STARK curve signing path via {@link StarknetTokenService#invokeContract} rather than Web3j.
 *
 * <p>The Cairo ERC-3525 contract exposes:
 * <ul>
 *   <li>{@code pause_slot(slot: u256)} — block all value transfers in a series</li>
 *   <li>{@code unpause_slot(slot: u256)}</li>
 *   <li>{@code freeze_token(token_id: u256, reason: felt252)}</li>
 *   <li>{@code unfreeze_token(token_id: u256)}</li>
 *   <li>{@code forced_transfer_value(from_id: u256, to_id: u256, value: u256, legal_basis: felt252)}</li>
 * </ul>
 */
@Service
@Transactional
public class StarknetErc3525AdminService {

    private static final Logger log = LoggerFactory.getLogger(StarknetErc3525AdminService.class);

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetSlotRepository slotRepository;
    private final AssetTokenUnitRepository tokenUnitRepository;
    private final StarknetTokenService starknetTokenService;

    public StarknetErc3525AdminService(
            AssetDeploymentRepository deploymentRepository,
            AssetSlotRepository slotRepository,
            AssetTokenUnitRepository tokenUnitRepository,
            StarknetTokenService starknetTokenService) {
        this.deploymentRepository = deploymentRepository;
        this.slotRepository       = slotRepository;
        this.tokenUnitRepository  = tokenUnitRepository;
        this.starknetTokenService = starknetTokenService;
    }

    // ── Slot operations ───────────────────────────────────────────────────────

    public CompletableFuture<String> pauseSlot(UUID deploymentId, BigInteger slotId) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("Starknet ERC-3525 pauseSlot={} on deployment={}", slotId, deploymentId);

        slotRepository.findByAssetIdAndSlotId(dep.getAssetId(), slotId).ifPresent(slot -> {
            slot.setPaused(true);
            slotRepository.save(slot);
        });

        return starknetTokenService.invokeContract(
                dep.getNetwork(), dep.getContractAddress(), "pause_slot",
                slotFelts(slotId));
    }

    public CompletableFuture<String> unpauseSlot(UUID deploymentId, BigInteger slotId) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("Starknet ERC-3525 unpauseSlot={} on deployment={}", slotId, deploymentId);

        slotRepository.findByAssetIdAndSlotId(dep.getAssetId(), slotId).ifPresent(slot -> {
            slot.setPaused(false);
            slotRepository.save(slot);
        });

        return starknetTokenService.invokeContract(
                dep.getNetwork(), dep.getContractAddress(), "unpause_slot",
                slotFelts(slotId));
    }

    public CompletableFuture<String> createSlot(UUID deploymentId, BigInteger slotId,
                                                  String name, Map<String, Object> metadata,
                                                  BigInteger supplyCap) {
        AssetDeployment dep = requireDeployment(deploymentId);
        AssetSlot slot = new AssetSlot();
        slot.setAssetId(dep.getAssetId());
        slot.setSlotId(slotId);
        slot.setName(name);
        slot.setMetadata(metadata);
        slot.setSupplyCap(supplyCap);
        slotRepository.save(slot);

        if (supplyCap != null && supplyCap.compareTo(BigInteger.ZERO) > 0) {
            return starknetTokenService.invokeContract(
                    dep.getNetwork(), dep.getContractAddress(), "set_slot_supply_cap",
                    concat(slotFelts(slotId), slotFelts(supplyCap)));
        }
        return CompletableFuture.completedFuture("SLOT_CREATED_OFFCHAIN_ONLY");
    }

    // ── Token operations ──────────────────────────────────────────────────────

    public CompletableFuture<String> freezeToken(UUID deploymentId, BigInteger tokenId, String reason) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("Starknet ERC-3525 freezeToken={} reason={} on deployment={}", tokenId, reason, deploymentId);

        tokenUnitRepository.findByAssetIdAndTokenId(dep.getAssetId(), tokenId).ifPresent(unit -> {
            unit.setFrozen(true);
            unit.setFreezeReason(reason);
            tokenUnitRepository.save(unit);
        });

        return starknetTokenService.invokeContract(
                dep.getNetwork(), dep.getContractAddress(), "freeze_token",
                concat(slotFelts(tokenId), java.util.List.of(starknetTokenService.shortStringToFeltPublic(reason))));
    }

    public CompletableFuture<String> unfreezeToken(UUID deploymentId, BigInteger tokenId) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("Starknet ERC-3525 unfreezeToken={} on deployment={}", tokenId, deploymentId);

        tokenUnitRepository.findByAssetIdAndTokenId(dep.getAssetId(), tokenId).ifPresent(unit -> {
            unit.setFrozen(false);
            unit.setFreezeReason(null);
            tokenUnitRepository.save(unit);
        });

        return starknetTokenService.invokeContract(
                dep.getNetwork(), dep.getContractAddress(), "unfreeze_token",
                slotFelts(tokenId));
    }

    public CompletableFuture<String> forcedValueTransfer(UUID deploymentId, BigInteger fromTokenId,
                                                           BigInteger toTokenId, BigInteger value,
                                                           String legalBasis) {
        AssetDeployment dep = requireDeployment(deploymentId);
        log.info("Starknet ERC-3525 forcedValueTransfer: from={} to={} value={} on deployment={}",
                fromTokenId, toTokenId, value, deploymentId);

        return starknetTokenService.invokeContract(
                dep.getNetwork(), dep.getContractAddress(), "forced_transfer_value",
                concat(slotFelts(fromTokenId), slotFelts(toTokenId), slotFelts(value),
                       java.util.List.of(starknetTokenService.shortStringToFeltPublic(legalBasis))));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AssetDeployment requireDeployment(UUID deploymentId) {
        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (dep.getContractAddress() == null) {
            throw new IllegalStateException("Starknet ERC-3525 not yet deployed: deploymentId=" + deploymentId);
        }
        return dep;
    }

    /**
     * Encodes a BigInteger as a Cairo u256 (two felts: low, high).
     */
    private static java.util.List<BigInteger> slotFelts(BigInteger value) {
        BigInteger mask128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        BigInteger low  = value.and(mask128);
        BigInteger high = value.shiftRight(128).and(mask128);
        return java.util.List.of(low, high);
    }

    @SafeVarargs
    private static java.util.List<BigInteger> concat(java.util.List<BigInteger>... lists) {
        java.util.List<BigInteger> result = new java.util.ArrayList<>();
        for (var list : lists) result.addAll(list);
        return result;
    }
}
