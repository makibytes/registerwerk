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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

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
 * confirmations are journaled via {@link ChainEffectRecorder} — a reorg deep enough to retract the
 * confirming block still reverts them (see {@link VaultNavStrikeRevertCompensator} and
 * {@link VaultRequestFulfillmentRevertCompensator}). Deposit-cap confirmations are deliberately
 * <b>not</b> journaled: a stale cap after a deep reorg is a soft, non-funds-affecting
 * inconsistency, and there is no compensator registered for it — see {@link #resolveDepositCap}.
 */
@Component
class VaultConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(VaultConfirmationListener.class);

    private final VaultNavStrikeRepository navStrikeRepository;
    private final VaultRequestRepository vaultRequestRepository;
    private final AssetVaultStateRepository vaultStateRepository;
    private final BlockchainTransactionService blockchainTransactionService;
    private final ChainEffectRecorder chainEffectRecorder;

    VaultConfirmationListener(
            VaultNavStrikeRepository navStrikeRepository,
            VaultRequestRepository vaultRequestRepository,
            AssetVaultStateRepository vaultStateRepository,
            BlockchainTransactionService blockchainTransactionService,
            ChainEffectRecorder chainEffectRecorder) {
        this.navStrikeRepository = navStrikeRepository;
        this.vaultRequestRepository = vaultRequestRepository;
        this.vaultStateRepository = vaultStateRepository;
        this.blockchainTransactionService = blockchainTransactionService;
        this.chainEffectRecorder = chainEffectRecorder;
    }

    @SchedulerLock(name = "vaultConfirmationListener", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 40_000)
    @Transactional
    public void resolvePending() {
        for (VaultNavStrike strike : navStrikeRepository.findByTxHashIsNotNullAndConfirmedFalse()) {
            try {
                resolveNavStrike(strike);
            } catch (Exception e) {
                log.warn("Failed to resolve NAV strike={}: {}", strike.getId(), e.getMessage());
            }
        }
        for (VaultRequest request : vaultRequestRepository.findByFulfilledTxIsNotNullAndConfirmedFalse()) {
            try {
                resolveFulfillment(request);
            } catch (Exception e) {
                log.warn("Failed to resolve fulfilment for VaultRequest={}: {}", request.getId(), e.getMessage());
            }
        }
        for (VaultRequest request : vaultRequestRepository.findByCancelledTxIsNotNullAndConfirmedFalse()) {
            try {
                resolveCancellation(request);
            } catch (Exception e) {
                log.warn("Failed to resolve cancellation for VaultRequest={}: {}", request.getId(), e.getMessage());
            }
        }
        for (AssetVaultState state : vaultStateRepository.findByDepositCapTxHashIsNotNull()) {
            try {
                resolveDepositCap(state);
            } catch (Exception e) {
                log.warn("Failed to resolve deposit cap for asset={}: {}", state.getAssetId(), e.getMessage());
            }
        }
    }

    private void resolveNavStrike(VaultNavStrike strike) {
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
        navStrikeRepository.save(strike);

        long highestConfirmedStrikeId = navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(strike.getAssetId())
                .stream()
                .filter(VaultNavStrike::isConfirmed)
                .mapToLong(VaultNavStrike::getStrikeId)
                .max()
                .orElse(strike.getStrikeId());
        if (highestConfirmedStrikeId != strike.getStrikeId()) {
            log.info("VaultNavStrike={} confirmed but a newer strike is already confirmed for asset={}; "
                    + "not applying to AssetVaultState.", strike.getId(), strike.getAssetId());
            return;
        }

        AssetVaultState state = vaultStateRepository.findById(strike.getAssetId())
                .orElseGet(() -> { AssetVaultState s = new AssetVaultState(); s.setAssetId(strike.getAssetId()); return s; });
        state.setLatestNavPerShare(strike.getNavPerShare());
        state.setLatestNavStrikeAt(strike.getEffectiveAt());
        state.setLatestNavReportHash(strike.getReportHash());
        vaultStateRepository.save(state);

        chainEffectRecorder.record(ChainEffectDescriptor.of(
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
        vaultRequestRepository.save(request);

        chainEffectRecorder.record(ChainEffectDescriptor.of(
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
        vaultRequestRepository.save(request);

        chainEffectRecorder.record(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "blockchain", VaultRequestFulfillmentRevertCompensator.EFFECT_TYPE,
                "VaultRequest", request.getId(), request.getAssetId(),
                CompensationCategory.INVERSE_FLIP));
    }

    private void resolveDepositCap(AssetVaultState state) {
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
        state.setDepositCap(state.getPendingDepositCap());
        state.setPendingDepositCap(null);
        state.setDepositCapTxHash(null);
        state.setDepositCapChainConfigId(location.get().chainConfigId());
        state.setDepositCapBlockNumber(location.get().blockNumber());
        vaultStateRepository.save(state);
        // Deliberately not journaled via ChainEffectRecorder — see class javadoc.
    }
}
