package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import org.springframework.stereotype.Component;

/** Returns an orphaned, confirmed reinstatement to fail-closed receipt verification. */
@Component
class OrgReinstatementRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ORG_REINSTATEMENT_CONFIRMED";
    private final OrgRegistrationRepository repository;

    OrgReinstatementRevertCompensator(OrgRegistrationRepository repository) {
        this.repository = repository;
    }

    @Override public String effectType() { return EFFECT_TYPE; }
    @Override public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        var registration = repository.findById(effect.entityId()).orElse(null);
        if (registration == null) {
            return new CompensationOutcome.NotApplicable("OrgRegistration no longer exists");
        }
        if (registration.getStatus() != OrgRegistrationStatus.ACTIVE) {
            return new CompensationOutcome.NotApplicable("Reinstatement no longer owns registration state");
        }
        if (!ChainEffectCausality.matches(effect, registration.getStatusChainConfigId(),
                registration.getStatusTx(), registration.getStatusBlockNumber(),
                registration.getStatusBlockHash())) {
            return new CompensationOutcome.NotApplicable("Reinstatement is owned by a different incarnation");
        }
        registration.setStatus(OrgRegistrationStatus.REINSTATE_PENDING);
        OrgSuspensionRevertCompensator.clearBlockCausality(registration);
        repository.save(registration);
        return new CompensationOutcome.Compensated("Returned reinstatement to fail-closed verification");
    }
}
