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
 * The INVERSE_FLIP compensator for {@code ERC3643_IDENTITY_REMOVED}. A retracted confirming block
 * clears only the chain-derived confirmation flag and exact block provenance. It deliberately
 * preserves (or restores) {@code removedAt} and {@code removedByTx}: removing an identity narrows
 * compliance access, so the submitted removal must remain fail-closed while that same transaction
 * awaits a new canonical verdict. Only a confirmed failed receipt may reactivate the entry by
 * clearing {@code removedAt} (see {@link Erc3643IdentityRegistryConfirmationListener}).
 *
 * <p>See {@link IdentityRegistryRegistrationRevertCompensator}'s javadoc for the shared design
 * rationale (direct repository access, never through a writer/listener that depends on
 * {@code ChainEffectRecorder}).
 */
@Component
class IdentityRegistryRemovalRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ERC3643_IDENTITY_REMOVED";

    private static final Logger log = LoggerFactory.getLogger(IdentityRegistryRemovalRevertCompensator.class);

    private final Erc3643IdentityRegistryRepository repository;

    IdentityRegistryRemovalRevertCompensator(Erc3643IdentityRegistryRepository repository) {
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
        if (!ChainEffectCausality.matches(effect, entry.getChainConfigId(), entry.getRemovedByTx(),
                entry.getRemovalBlockNumber(), entry.getRemovalBlockHash())) {
            return new CompensationOutcome.NotApplicable(
                    "Erc3643IdentityRegistry " + id + " is owned by a different removal incarnation");
        }

        log.error("Erc3643IdentityRegistry id={} wallet={} removal confirmation was retracted "
                        + "by a reorg; retaining fail-closed removal intent while the transaction "
                        + "awaits a new canonical verdict.",
                id, entry.getWalletAddress());
        if (entry.getRemovedAt() == null) {
            entry.setRemovedAt(Instant.now());
        }
        entry.setRemovalConfirmed(false);
        entry.setRemovalBlockNumber(null);
        entry.setRemovalBlockHash(null);
        repository.save(entry);

        return new CompensationOutcome.Compensated(
                "Returned Erc3643IdentityRegistry " + id + " removal to pending after retraction");
    }
}
