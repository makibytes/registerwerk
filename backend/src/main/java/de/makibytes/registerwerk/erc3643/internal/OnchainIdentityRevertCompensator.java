package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code ONCHAIN_IDENTITY_DEPLOYED} — undoes an
 * {@link OnchainIdentity} resolved to a real proxy address whose confirming block was later
 * retracted, reverting it back to a pending placeholder so
 * {@link OnchainIdentityReceiptListener#resolvePendingIdentities} picks it up again. Talks to
 * {@link OnchainIdentityRepository} directly, never {@code OnchainIdentityReceiptListener} (which
 * depends on {@code ChainEffectRecorder} — see
 * {@code blockchain.internal.tx.BlockchainTxRevertCompensator}'s javadoc for why).
 */
@Component
class OnchainIdentityRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ONCHAIN_IDENTITY_DEPLOYED";

    private static final Logger log = LoggerFactory.getLogger(OnchainIdentityRevertCompensator.class);

    private final OnchainIdentityRepository repository;

    OnchainIdentityRevertCompensator(OnchainIdentityRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        OnchainIdentity identity = repository.findById(id).orElse(null);
        if (identity == null) {
            return new CompensationOutcome.NotApplicable("OnchainIdentity " + id + " no longer exists");
        }
        if (identity.getIdentityAddress() == null || identity.getIdentityAddress().startsWith("0x-PENDING-")
                || identity.getIdentityAddress().startsWith("0x-FAILED-")) {
            return new CompensationOutcome.NotApplicable(
                    "OnchainIdentity " + id + " is already pending/failed, not a resolved address");
        }

        log.error("OnchainIdentity id={} address={} but its confirming block was retracted by a reorg "
                        + "— reverting to pending for re-verification.",
                id, identity.getIdentityAddress());
        identity.setIdentityAddress("0x-PENDING-ONCHAINID-" + UUID.randomUUID());
        repository.save(identity);

        return new CompensationOutcome.Compensated("Reverted OnchainIdentity " + id + " to pending after retraction");
    }
}
