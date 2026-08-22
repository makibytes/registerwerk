package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Closes the gap {@link IdentityRegistryService}'s javadoc documents but does not itself fix:
 * {@code registerInvestor}/{@code removeInvestor} write {@code erc3643_identity_registry} rows
 * optimistically, at submission time, with no confirmation-gated moment of their own — unlike
 * {@code blockchain_transaction}/{@code asset_deployment}/{@code orgidentity}'s equivalents, which
 * all wait for {@link de.makibytes.registerwerk.finality.api.FinalityLevel#FINALIZED} before
 * asserting a terminal state. This listener reconciles against
 * {@link BlockchainTransactionService}'s own tracked verdict for {@code registered_by_tx}/
 * {@code removed_by_tx} (already model-aware, already reorg-guarded — see that service's poller)
 * and, on SUCCESS, journals a {@code ChainEffectDescriptor} so a reorg deep enough to retract the
 * confirming block still reverts the register's claim (see the two {@code *RevertCompensator}
 * classes in this package).
 *
 * <p>Deliberately does not react to FAILED here beyond marking the row no-longer-pending: the
 * optimistic-write risk profile for a straightforwardly failed (never-succeeded) tx is pre-existing
 * and unrelated to reorg compensation — expanding it is out of this listener's scope.
 */
@Component
class Erc3643IdentityRegistryConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(Erc3643IdentityRegistryConfirmationListener.class);

    private final Erc3643IdentityRegistryRepository repository;
    private final BlockchainTransactionService blockchainTransactionService;
    private final ChainEffectRecorder chainEffectRecorder;

    Erc3643IdentityRegistryConfirmationListener(
            Erc3643IdentityRegistryRepository repository,
            BlockchainTransactionService blockchainTransactionService,
            ChainEffectRecorder chainEffectRecorder) {
        this.repository = repository;
        this.blockchainTransactionService = blockchainTransactionService;
        this.chainEffectRecorder = chainEffectRecorder;
    }

    @SchedulerLock(name = "erc3643IdentityRegistryConfirmationListener", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 35_000)
    @Transactional
    public void resolvePending() {
        for (Erc3643IdentityRegistry entry : repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()) {
            try {
                resolveRegistration(entry);
            } catch (Exception e) {
                log.warn("Failed to resolve registration for erc3643_identity_registry={}: {}", entry.getId(), e.getMessage());
            }
        }
        for (Erc3643IdentityRegistry entry : repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()) {
            try {
                resolveRemoval(entry);
            } catch (Exception e) {
                log.warn("Failed to resolve removal for erc3643_identity_registry={}: {}", entry.getId(), e.getMessage());
            }
        }
    }

    private void resolveRegistration(Erc3643IdentityRegistry entry) {
        String txHash = entry.getRegisteredByTx();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("erc3643_identity_registry={} registerIdentity tx={} failed on-chain; leaving row as-is "
                    + "(pre-existing optimistic-write risk, not this listener's concern) but no longer polling.",
                    entry.getId(), txHash);
            entry.setRegistrationConfirmed(true);
            repository.save(entry);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return; // not yet mined, or mined but not yet final — recheck next tick
        }
        entry.setRegistrationConfirmed(true);
        repository.save(entry);
        chainEffectRecorder.record(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "erc3643", IdentityRegistryRegistrationRevertCompensator.EFFECT_TYPE,
                "Erc3643IdentityRegistry", entry.getId(), null,
                CompensationCategory.INVERSE_FLIP));
    }

    private void resolveRemoval(Erc3643IdentityRegistry entry) {
        String txHash = entry.getRemovedByTx();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("erc3643_identity_registry={} deleteIdentity tx={} failed on-chain; leaving row as-is "
                    + "(pre-existing optimistic-write risk, not this listener's concern) but no longer polling.",
                    entry.getId(), txHash);
            entry.setRemovalConfirmed(true);
            repository.save(entry);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return;
        }
        entry.setRemovalConfirmed(true);
        repository.save(entry);
        chainEffectRecorder.record(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "erc3643", IdentityRegistryRemovalRevertCompensator.EFFECT_TYPE,
                "Erc3643IdentityRegistry", entry.getId(), null,
                CompensationCategory.INVERSE_FLIP));
    }
}
