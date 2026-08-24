package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Closes the gap {@link Erc4626AdminService}/{@link Erc7540AdminService}'s javadoc documents but
 * does not itself fix: {@code strikeNav}/{@code setDepositCap}/{@code fulfillRequest}/
 * {@code cancelRequest} submit their EVM transaction and return before any receipt exists, so
 * they deliberately leave {@code vault_nav_strike}/{@code vault_request}/{@code
 * asset_vault_state}'s pending columns unconfirmed rather than asserting a terminal state early —
 * exactly the "async submit + track + poll + confirm" pattern already used for
 * {@code erc3643_identity_registry} (see {@code Erc3643IdentityRegistryConfirmationListener}).
 *
 * <p>NAV strikes and request fulfilment/cancellation affect share pricing and balances, so their
 * confirmations are journaled via {@link ChainEffectRecorder} — an eligible routine retraction
 * reverts them, while a finality violation quarantines the chain (see {@link VaultNavStrikeRevertCompensator} and
 * {@link VaultRequestFulfillmentRevertCompensator}). Deposit caps control which future deposits
 * the vault accepts and are therefore journaled too; their pre-image is retained so a retraction
 * can restore the last canonical cap without guessing.
 */
@Component
class VaultConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(VaultConfirmationListener.class);

    private final VaultNavStrikeRepository navStrikeRepository;
    private final VaultRequestRepository vaultRequestRepository;
    private final AssetVaultStateRepository vaultStateRepository;
    private final BlockchainTransactionService blockchainTransactionService;
    private final ChainEffectRecorder chainEffectRecorder;
    private final IsolatedTransactionExecutor isolatedTransactions;

    VaultConfirmationListener(
            VaultNavStrikeRepository navStrikeRepository,
            VaultRequestRepository vaultRequestRepository,
            AssetVaultStateRepository vaultStateRepository,
            BlockchainTransactionService blockchainTransactionService,
            ChainEffectRecorder chainEffectRecorder,
            IsolatedTransactionExecutor isolatedTransactions) {
        this.navStrikeRepository = navStrikeRepository;
        this.vaultRequestRepository = vaultRequestRepository;
        this.vaultStateRepository = vaultStateRepository;
        this.blockchainTransactionService = blockchainTransactionService;
        this.chainEffectRecorder = chainEffectRecorder;
        this.isolatedTransactions = isolatedTransactions;
    }

    @SchedulerLock(name = "vaultConfirmationListener", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 40_000)
    public void resolvePending() {
        for (VaultNavStrike strike : navStrikeRepository.findByTxHashIsNotNullAndConfirmedFalse()) {
            try {
                isolatedTransactions.run(() -> resolveNavStrike(strike));
            } catch (Exception e) {
                log.warn("Failed to resolve NAV strike={}: {}", strike.getId(), e.getMessage());
            }
        }
        for (VaultRequest request : vaultRequestRepository.findByFulfilledTxIsNotNullAndConfirmedFalse()) {
            try {
                isolatedTransactions.run(() -> resolveFulfillment(request));
            } catch (Exception e) {
                log.warn("Failed to resolve fulfilment for VaultRequest={}: {}", request.getId(), e.getMessage());
            }
        }
        for (VaultRequest request : vaultRequestRepository.findByCancelledTxIsNotNullAndConfirmedFalse()) {
            try {
                isolatedTransactions.run(() -> resolveCancellation(request));
            } catch (Exception e) {
                log.warn("Failed to resolve cancellation for VaultRequest={}: {}", request.getId(), e.getMessage());
            }
        }
        for (AssetVaultState state : vaultStateRepository.findByDepositCapTxHashIsNotNull()) {
            try {
                isolatedTransactions.run(() -> resolveDepositCap(state));
            } catch (Exception e) {
                log.warn("Failed to resolve deposit cap for asset={}: {}", state.getAssetId(), e.getMessage());
            }
        }
    }

    private void resolveNavStrike(VaultNavStrike strike) {
        strike = navStrikeRepository.findByIdForUpdate(strike.getId()).orElse(strike);
        String txHash = strike.getTxHash();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("VaultNavStrike={} setNavPerShare tx={} failed on-chain; leaving AssetVaultState "
                    + "untouched (strike row stays as a failed-attempt record) but no longer polling.",
                    strike.getId(), txHash);
            strike.setConfirmed(true);
            navStrikeRepository.save(strike);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return; // not yet mined, or mined but not yet final — recheck next tick
        }
        strike.setConfirmed(true);
        strike.setChainConfigId(location.get().chainConfigId());
        strike.setBlockNumber(location.get().blockNumber());
        strike.setBlockHash(location.get().blockHash());
        navStrikeRepository.save(strike);

        long highestConfirmedStrikeId = navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(strike.getAssetId())
                .stream()
                .filter(VaultConfirmationListener::hasCanonicalConfirmation)
                .mapToLong(VaultNavStrike::getStrikeId)
                .max()
                .orElse(strike.getStrikeId());
        if (highestConfirmedStrikeId != strike.getStrikeId()) {
            log.info("VaultNavStrike={} confirmed but a newer strike is already confirmed for asset={}; "
                    + "not applying to AssetVaultState.", strike.getId(), strike.getAssetId());
            return;
        }

        UUID assetId = strike.getAssetId();
        AssetVaultState state = vaultStateRepository.findByAssetIdForUpdate(assetId)
                .orElseGet(() -> { AssetVaultState s = new AssetVaultState(); s.setAssetId(assetId); return s; });
        state.setLatestNavPerShare(strike.getNavPerShare());
        state.setLatestNavStrikeAt(strike.getEffectiveAt());
        state.setLatestNavStrikeId(strike.getId());
        state.setLatestNavReportHash(strike.getReportHash());
        vaultStateRepository.save(state);

        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "blockchain", VaultNavStrikeRevertCompensator.EFFECT_TYPE,
                "VaultNavStrike", strike.getId(), strike.getAssetId(),
                CompensationCategory.INVERSE_FLIP));
    }

    private void resolveFulfillment(VaultRequest request) {
        String txHash = request.getFulfilledTx();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("VaultRequest={} fulfil tx={} failed on-chain; clearing so it can be resubmitted.",
                    request.getId(), txHash);
            request.setFulfilledTx(null);
            request.setNavAtFulfill(null);
            vaultRequestRepository.save(request);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return;
        }
        request.setRequestStatus(VaultRequestStatus.FULFILLED);
        request.setFulfilledAt(Instant.now());
        request.setConfirmed(true);
        request.setChainConfigId(location.get().chainConfigId());
        request.setBlockNumber(location.get().blockNumber());
        request.setBlockHash(location.get().blockHash());
        vaultRequestRepository.save(request);

        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "blockchain", VaultRequestFulfillmentRevertCompensator.EFFECT_TYPE,
                "VaultRequest", request.getId(), request.getAssetId(),
                CompensationCategory.INVERSE_FLIP));
    }

    private void resolveCancellation(VaultRequest request) {
        String txHash = request.getCancelledTx();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("VaultRequest={} cancel tx={} failed on-chain; clearing so it can be resubmitted.",
                    request.getId(), txHash);
            request.setCancelledTx(null);
            vaultRequestRepository.save(request);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return;
        }
        request.setRequestStatus(VaultRequestStatus.CANCELLED);
        request.setConfirmed(true);
        request.setChainConfigId(location.get().chainConfigId());
        request.setBlockNumber(location.get().blockNumber());
        request.setBlockHash(location.get().blockHash());
        vaultRequestRepository.save(request);

        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "blockchain", VaultRequestFulfillmentRevertCompensator.EFFECT_TYPE,
                "VaultRequest", request.getId(), request.getAssetId(),
                CompensationCategory.INVERSE_FLIP));
    }

    private void resolveDepositCap(AssetVaultState state) {
        state = vaultStateRepository.findByAssetIdForUpdate(state.getAssetId()).orElse(state);
        String txHash = state.getDepositCapTxHash();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("AssetVaultState asset={} setDepositCap tx={} failed on-chain; discarding pending value.",
                    state.getAssetId(), txHash);
            state.setPendingDepositCap(null);
            state.setDepositCapTxHash(null);
            vaultStateRepository.save(state);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return;
        }
        java.math.BigInteger before = state.getDepositCap();
        java.math.BigInteger after = state.getPendingDepositCap();
        Map<String, Object> beforeState = new HashMap<>();
        beforeState.put("depositCap", before != null ? before.toString() : null);
        beforeState.put("chainConfigId", nullableString(state.getDepositCapChainConfigId()));
        beforeState.put("blockNumber", state.getDepositCapBlockNumber());
        beforeState.put("blockHash", state.getDepositCapBlockHash());
        beforeState.put("txHash", state.getDepositCapConfirmedTxHash());

        state.setDepositCap(after);
        state.setPendingDepositCap(null);
        state.setDepositCapTxHash(null);
        state.setDepositCapChainConfigId(location.get().chainConfigId());
        state.setDepositCapBlockNumber(location.get().blockNumber());
        state.setDepositCapBlockHash(location.get().blockHash());
        state.setDepositCapConfirmedTxHash(txHash);
        vaultStateRepository.save(state);

        Map<String, Object> afterState = new HashMap<>();
        afterState.put("depositCap", after.toString());
        afterState.put("chainConfigId", location.get().chainConfigId().toString());
        afterState.put("blockNumber", location.get().blockNumber());
        afterState.put("blockHash", location.get().blockHash());
        afterState.put("txHash", txHash);
        chainEffectRecorder.recordFinalized(new ChainEffectDescriptor(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                null, "blockchain", VaultDepositCapRevertCompensator.EFFECT_TYPE,
                "AssetVaultState", state.getAssetId(), state.getAssetId(), CompensationCategory.INVERSE_FLIP,
                beforeState, afterState, null, null));
    }

    private static boolean hasCanonicalConfirmation(VaultNavStrike strike) {
        return strike.isConfirmed()
                && strike.getChainConfigId() != null
                && strike.getBlockNumber() != null
                && strike.getBlockHash() != null;
    }

    private static String nullableString(Object value) {
        return value != null ? value.toString() : null;
    }
}
