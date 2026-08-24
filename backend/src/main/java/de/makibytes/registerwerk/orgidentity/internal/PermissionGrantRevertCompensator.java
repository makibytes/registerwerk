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
        boolean pendingRevocationIntent = grant.getStatus() == PermissionGrantStatus.REVOCATION_PENDING
                && grant.getRevokedAt() != null
                && grant.getRevokedChainConfigId() == null
                && grant.getRevokedBlockNumber() == null
                && grant.getRevokedBlockHash() == null;
        if (grant.getStatus() != PermissionGrantStatus.ACTIVE && !pendingRevocationIntent) {
            return new CompensationOutcome.NotApplicable(
                    "PermissionGrant " + id + " is neither ACTIVE nor awaiting revocation (status="
                            + grant.getStatus() + ")");
        }
        if (!ChainEffectCausality.matches(effect, grant.getGrantedChainConfigId(), grant.getGrantedTx(),
                grant.getGrantedBlockNumber(), grant.getGrantedBlockHash())) {
            return new CompensationOutcome.NotApplicable(
                    "PermissionGrant " + id + " is owned by a different confirmation incarnation");
        }

        log.error("PermissionGrant id={} grant confirmation was retracted by a reorg (status={}) "
                        + "— clearing grant provenance while preserving any fail-closed revocation intent.",
                id, grant.getStatus());
        if (!pendingRevocationIntent) {
            grant.setStatus(PermissionGrantStatus.PENDING);
        }
        grant.setGrantedChainConfigId(null);
        grant.setGrantedBlockNumber(null);
        grant.setGrantedBlockHash(null);
        repository.save(grant);

        return new CompensationOutcome.Compensated(
                "Cleared PermissionGrant " + id + " grant confirmation after retraction");
    }
}
