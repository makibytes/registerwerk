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

import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code ERC3643_IDENTITY_REMOVED} — undoes an
 * {@link Erc3643IdentityRegistry} soft-removal whose confirming {@code deleteIdentity} block was
 * later retracted, by clearing {@code removedAt} — the investor was never actually removed
 * on-chain. See {@link IdentityRegistryRegistrationRevertCompensator}'s javadoc for the shared
 * design rationale (direct repository access, never through a writer/listener that depends on
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
        if (entry.isActive()) {
            return new CompensationOutcome.NotApplicable(
                    "Erc3643IdentityRegistry " + id + " is already active (not removed)");
        }

        log.error("Erc3643IdentityRegistry id={} wallet={} was removed but its confirming block was retracted "
                        + "by a reorg — the investor was never actually removed "
                        + "on-chain; restoring this entry to active.",
                id, entry.getWalletAddress());
        entry.setRemovedAt(null);
        repository.save(entry);

        return new CompensationOutcome.Compensated("Restored Erc3643IdentityRegistry " + id + " to active after retraction");
    }
}
