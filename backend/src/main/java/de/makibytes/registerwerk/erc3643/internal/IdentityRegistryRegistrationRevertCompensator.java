package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistry;
import de.makibytes.registerwerk.erc3643.api.Erc3643IdentityRegistryRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code ERC3643_IDENTITY_REGISTERED} — undoes an
 * {@link Erc3643IdentityRegistry} entry whose confirming {@code registerIdentity} block was later
 * retracted. Soft-removes it (sets {@code removedAt}, same convention this table already uses for
 * a deliberate {@code deleteIdentity}) rather than hard-deleting — the row's existence is itself
 * part of the eWpG-style audit trail of "this registration was attempted", even once undone.
 *
 * <p>Talks to {@link Erc3643IdentityRegistryRepository} directly, never
 * {@code Erc3643IdentityRegistryConfirmationListener} or {@code IdentityRegistryService} (both
 * depend on {@code ChainEffectRecorder} — see
 * {@code blockchain.internal.tx.BlockchainTxRevertCompensator}'s javadoc for why).
 */
@Component
class IdentityRegistryRegistrationRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ERC3643_IDENTITY_REGISTERED";

    private static final Logger log = LoggerFactory.getLogger(IdentityRegistryRegistrationRevertCompensator.class);

    private final Erc3643IdentityRegistryRepository repository;

    IdentityRegistryRegistrationRevertCompensator(Erc3643IdentityRegistryRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        Erc3643IdentityRegistry entry = repository.findById(id).orElse(null);
        if (entry == null) {
            return new CompensationOutcome.NotApplicable("Erc3643IdentityRegistry " + id + " no longer exists");
        }
        if (!ChainEffectCausality.matches(effect, entry.getChainConfigId(), entry.getRegisteredByTx(),
                entry.getRegistrationBlockNumber(), entry.getRegistrationBlockHash())) {
            return new CompensationOutcome.NotApplicable(
                    "Erc3643IdentityRegistry " + id + " is owned by a different registration incarnation");
        }
        boolean pendingRemovalIntent = !entry.isActive()
                && entry.getRemovedByTx() != null
                && !entry.isRemovalConfirmed()
                && entry.getRemovalBlockNumber() == null
                && entry.getRemovalBlockHash() == null;
        if (!entry.isActive() && !pendingRemovalIntent) {
            return new CompensationOutcome.NotApplicable(
                    "Erc3643IdentityRegistry " + id + " is independently removed (removedAt="
                            + entry.getRemovedAt() + ")");
        }

        log.error("Erc3643IdentityRegistry id={} wallet={} was registered but its confirming block was "
                        + "retracted by a reorg — the investor was never "
                        + "actually registered on-chain; soft-removing this entry.",
                id, entry.getWalletAddress());
        if (entry.getRemovedAt() == null) {
            entry.setRemovedAt(Instant.now());
        }
        entry.setRegistrationConfirmed(false);
        entry.setRegistrationBlockNumber(null);
        entry.setRegistrationBlockHash(null);
        repository.save(entry);

        return new CompensationOutcome.Compensated("Soft-removed Erc3643IdentityRegistry " + id + " after retraction");
    }
}
