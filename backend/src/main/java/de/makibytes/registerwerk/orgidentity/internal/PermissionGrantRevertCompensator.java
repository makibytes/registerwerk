package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The INVERSE_FLIP compensator for {@code PERMISSION_GRANTED} — see
 *  {@link OrgRegistrationRevertCompensator}'s javadoc for the shared design rationale. */
@Component
class PermissionGrantRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "PERMISSION_GRANTED";

    private static final Logger log = LoggerFactory.getLogger(PermissionGrantRevertCompensator.class);

    private final PermissionGrantRepository repository;

    PermissionGrantRevertCompensator(PermissionGrantRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        PermissionGrant grant = repository.findById(id).orElse(null);
        if (grant == null) {
            return new CompensationOutcome.NotApplicable("PermissionGrant " + id + " no longer exists");
        }
        if (grant.getStatus() != PermissionGrantStatus.ACTIVE) {
            return new CompensationOutcome.NotApplicable(
                    "PermissionGrant " + id + " is no longer ACTIVE (status=" + grant.getStatus() + ")");
        }

        log.error("PermissionGrant id={} was ACTIVE but its confirming block was retracted by a reorg "
                        + "— reverting to PENDING for re-verification.", id);
        grant.setStatus(PermissionGrantStatus.PENDING);
        repository.save(grant);

        return new CompensationOutcome.Compensated("Reverted PermissionGrant " + id + " to PENDING after retraction");
    }
}
