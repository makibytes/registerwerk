package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import org.springframework.stereotype.Component;

/** Returns an orphaned, confirmed suspension to fail-closed receipt verification. */
@Component
class OrgSuspensionRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ORG_SUSPENSION_CONFIRMED";
    private final OrgRegistrationRepository repository;

    OrgSuspensionRevertCompensator(OrgRegistrationRepository repository) {
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
        boolean supersededByPendingReinstatement =
                registration.getStatus() == OrgRegistrationStatus.REINSTATE_PENDING
                        && registration.getStatusRequestedAt() != null
                        && registration.getStatusChainConfigId() == null
                        && registration.getStatusBlockNumber() == null
                        && registration.getStatusBlockHash() == null;
        if (supersededByPendingReinstatement) {
            return new CompensationOutcome.Compensated(
                    "Suspension was superseded by a complete fail-closed reinstatement intent");
        }
        if (registration.getStatus() != OrgRegistrationStatus.SUSPENDED) {
            return new CompensationOutcome.NotApplicable("Suspension no longer owns registration state");
        }
        if (!ChainEffectCausality.matches(effect, registration.getStatusChainConfigId(),
                registration.getStatusTx(), registration.getStatusBlockNumber(),
                registration.getStatusBlockHash())) {
            return new CompensationOutcome.NotApplicable("Suspension is owned by a different incarnation");
        }
        registration.setStatus(OrgRegistrationStatus.SUSPEND_PENDING);
        clearBlockCausality(registration);
        repository.save(registration);
        return new CompensationOutcome.Compensated("Returned suspension to fail-closed verification");
    }

    static void clearBlockCausality(de.makibytes.registerwerk.orgidentity.api.OrgRegistration registration) {
        registration.setStatusChainConfigId(null);
        registration.setStatusBlockNumber(null);
        registration.setStatusBlockHash(null);
    }
}
