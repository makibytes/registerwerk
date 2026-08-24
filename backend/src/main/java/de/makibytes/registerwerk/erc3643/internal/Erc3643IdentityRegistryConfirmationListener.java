package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <p>A submitted removal is fail-closed immediately through {@code removedAt}. A successful final
 * receipt confirms and journals it; a reorg compensation keeps {@code removedAt} while returning
 * the receipt to pending; and only a confirmed failed receipt clears {@code removedAt} and safely
 * restores the investor to active.
 */
@Component
class Erc3643IdentityRegistryConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(Erc3643IdentityRegistryConfirmationListener.class);

    private final Erc3643IdentityRegistryRepository repository;
    private final BlockchainTransactionService blockchainTransactionService;
    private final ChainEffectRecorder chainEffectRecorder;
    private final IsolatedTransactionExecutor isolatedTransactions;

    Erc3643IdentityRegistryConfirmationListener(
            Erc3643IdentityRegistryRepository repository,
            BlockchainTransactionService blockchainTransactionService,
            ChainEffectRecorder chainEffectRecorder,
            IsolatedTransactionExecutor isolatedTransactions) {
        this.repository = repository;
        this.blockchainTransactionService = blockchainTransactionService;
        this.chainEffectRecorder = chainEffectRecorder;
        this.isolatedTransactions = isolatedTransactions;
    }

    @SchedulerLock(name = "erc3643IdentityRegistryConfirmationListener", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 35_000)
    public void resolvePending() {
        for (Erc3643IdentityRegistry entry : repository.findByRegisteredByTxIsNotNullAndRegistrationConfirmedFalse()) {
            try {
                isolatedTransactions.run(() -> resolveRegistration(entry));
            } catch (Exception e) {
                log.warn("Failed to resolve registration for erc3643_identity_registry={}: {}", entry.getId(), e.getMessage());
            }
        }
        for (Erc3643IdentityRegistry entry : repository.findByRemovedByTxIsNotNullAndRemovalConfirmedFalse()) {
            try {
                isolatedTransactions.run(() -> resolveRemoval(entry));
            } catch (Exception e) {
                log.warn("Failed to resolve removal for erc3643_identity_registry={}: {}", entry.getId(), e.getMessage());
            }
        }
    }

    private void resolveRegistration(Erc3643IdentityRegistry entry) {
        String txHash = entry.getRegisteredByTx();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("erc3643_identity_registry={} registerIdentity tx={} failed on-chain; "
                    + "soft-removing the optimistic mirror entry so compliance cannot fail open.",
                    entry.getId(), txHash);
            entry.setRegistrationConfirmed(true);
            entry.setRemovedAt(java.time.Instant.now());
            repository.save(entry);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return; // not yet mined, or mined but not yet final — recheck next tick
        }
        entry.setRegistrationConfirmed(true);
        entry.setRegistrationBlockNumber(location.get().blockNumber());
        entry.setRegistrationBlockHash(location.get().blockHash());
        // A re-included registration may reactivate a row soft-removed by registration
        // compensation, but it must never override a later deleteIdentity intent.
        if (entry.getRemovedByTx() == null) {
            entry.setRemovedAt(null);
        }
        repository.save(entry);
        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "erc3643", IdentityRegistryRegistrationRevertCompensator.EFFECT_TYPE,
                "Erc3643IdentityRegistry", entry.getId(), null,
                CompensationCategory.INVERSE_FLIP));
    }

    private void resolveRemoval(Erc3643IdentityRegistry entry) {
        String txHash = entry.getRemovedByTx();
        if (blockchainTransactionService.isConfirmedFailure(txHash)) {
            log.warn("erc3643_identity_registry={} deleteIdentity tx={} failed on-chain; "
                    + "restoring the optimistically removed mirror entry.",
                    entry.getId(), txHash);
            entry.setRemovalConfirmed(true);
            entry.setRemovedAt(null);
            entry.setRemovalBlockNumber(null);
            entry.setRemovalBlockHash(null);
            repository.save(entry);
            return;
        }
        Optional<BlockchainTransactionService.ConfirmedTxLocation> location =
                blockchainTransactionService.confirmedLocation(txHash);
        if (location.isEmpty()) {
            return;
        }
        entry.setRemovalConfirmed(true);
        entry.setRemovalBlockNumber(location.get().blockNumber());
        entry.setRemovalBlockHash(location.get().blockHash());
        entry.setRemovedAt(java.time.Instant.now());
        repository.save(entry);
        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                location.get().chainConfigId(), location.get().blockNumber(), location.get().blockHash(), txHash,
                "erc3643", IdentityRegistryRemovalRevertCompensator.EFFECT_TYPE,
                "Erc3643IdentityRegistry", entry.getId(), null,
                CompensationCategory.INVERSE_FLIP));
    }
}
