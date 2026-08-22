package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
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
 * Closes the gap {@link Erc3643DeploymentService}'s {@code issueKycClaim}/{@code revokeKycClaim}
 * javadoc documents but does not itself fix: both submit their EVM transaction and return before
 * any receipt exists, so {@link ClaimIssuanceService} deliberately leaves {@code onchain_claim}'s
 * confirmation columns unset rather than asserting compliance-relevant state early — the same
 * "async submit + track + poll + confirm" pattern already used for
 * {@code erc3643_identity_registry} (see {@link Erc3643IdentityRegistryConfirmationListener}) and
 * vault-admin ops (see {@code blockchain.internal.VaultConfirmationListener}).
 *
 * <p>Issuance confirmations are journaled via {@link ChainEffectRecorder} — a reorg deep enough to
 * retract the confirming {@code addClaim} block flips {@link OnchainClaim#isConfirmed()} back to
 * {@code false} (see {@link OnchainClaimRevertCompensator}), immediately excluding the claim from
 * {@code ClaimIssuanceService#getActiveClaims} again. Revocation confirmations are deliberately
 * <b>not</b> journaled: unlike a stale deposit cap, a stale-but-retracted revocation is fail-safe
 * (the claim simply stays excluded from compliance a little longer than strictly necessary) rather
 * than fail-open, so there is no compensator for it — see {@link #resolveRevocation}.
 */
@Component
class Erc3643ClaimConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(Erc3643ClaimConfirmationListener.class);

    private final OnchainClaimRepository claimRepository;
    private final BlockchainTransactionService blockchainTransactionService;
    private final ChainEffectRecorder chainEffectRecorder;

    Erc3643ClaimConfirmationListener(
            OnchainClaimRepository claimRepository,
            BlockchainTransactionService blockchainTransactionService,
            ChainEffectRecorder chainEffectRecorder) {
        this.claimRepository = claimRepository;
        this.blockchainTransactionService = blockchainTransactionService;
        this.chainEffectRecorder = chainEffectRecorder;
    }

    @SchedulerLock(name = "erc3643ClaimConfirmationListener", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    @Transactional
    public void resolvePending() {
        for (OnchainClaim claim : claimRepository.findByTxHashIsNotNullAndConfirmedFalse()) {
            try {
                resolveIssuance(claim);
            } catch (Exception e) {
                log.warn("Failed to resolve issuance for OnchainClaim={}: {}", claim.getId(), e.getMessage());
            }
        }
        for (OnchainClaim claim : claimRepository.findByRevocationTxHashIsNotNullAndRevokedAtIsNull()) {
            try {
                resolveRevocation(claim);
            } catch (Exception e) {
                log.warn("Failed to resolve revocation for OnchainClaim={}: {}", claim.getId(), e.getMessage());
            }
        }
    }

    private void resolveIssuance(OnchainClaim claim) {
        String txHash = claim.getTxHash();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("OnchainClaim={} addClaim tx={} failed on-chain; the claim never became valid — "
                    + "leaving confirmed=false permanently (excluded from getActiveClaims) but no longer polling.",
                    claim.getId(), txHash);
            // confirmed stays false, which already excludes it — nothing to flip. Mark by clearing
            // txHash so this row drops out of the polling query without a misleading "confirmed" flag.
            claim.setTxHash(null);
            claimRepository.save(claim);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return; // not yet mined, or mined but not yet final — recheck next tick
        }
        claim.setConfirmed(true);
        claim.setChainConfigId(location.get().chainConfigId());
        claim.setBlockNumber(location.get().blockNumber());
        claimRepository.save(claim);

        chainEffectRecorder.record(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "erc3643", OnchainClaimRevertCompensator.EFFECT_TYPE,
                "OnchainClaim", claim.getId(), null,
                CompensationCategory.INVERSE_FLIP));
    }

    private void resolveRevocation(OnchainClaim claim) {
        String txHash = claim.getRevocationTxHash();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("OnchainClaim={} removeClaim tx={} failed on-chain; clearing so revocation can be resubmitted.",
                    claim.getId(), txHash);
            claim.setRevocationTxHash(null);
            claimRepository.save(claim);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return;
        }
        claim.setRevokedAt(Instant.now());
        claimRepository.save(claim);
        // Deliberately not journaled via ChainEffectRecorder — see class javadoc.
    }
}
