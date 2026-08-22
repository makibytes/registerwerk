package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code ORG_REGISTRATION_CONFIRMED} — undoes an
 * {@link OrgRegistration} marked ACTIVE whose confirming block was later retracted. Talks to
 * {@link OrgRegistrationRepository} directly, never {@code OrgEcosystemTxPoller} (which depends on
 * {@code ChainEffectRecorder} — see {@code blockchain.internal.tx.BlockchainTxRevertCompensator}'s
 * javadoc for why routing through it would close a circular-bean dependency).
 */
@Component
class OrgRegistrationRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ORG_REGISTRATION_CONFIRMED";

    private static final Logger log = LoggerFactory.getLogger(OrgRegistrationRevertCompensator.class);

    private final OrgRegistrationRepository repository;

    OrgRegistrationRevertCompensator(OrgRegistrationRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        OrgRegistration registration = repository.findById(id).orElse(null);
        if (registration == null) {
            return new CompensationOutcome.NotApplicable("OrgRegistration " + id + " no longer exists");
        }
        if (registration.getStatus() != OrgRegistrationStatus.ACTIVE) {
            return new CompensationOutcome.NotApplicable(
                    "OrgRegistration " + id + " is no longer ACTIVE (status=" + registration.getStatus() + ")");
        }

        log.error("OrgRegistration id={} was ACTIVE but its confirming block was retracted by a reorg "
                        + "— reverting to PENDING for re-verification.", id);
        registration.setStatus(OrgRegistrationStatus.PENDING);
        repository.save(registration);

        return new CompensationOutcome.Compensated("Reverted OrgRegistration " + id + " to PENDING after retraction");
    }
}
