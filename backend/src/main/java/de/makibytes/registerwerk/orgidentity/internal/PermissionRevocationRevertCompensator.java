package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Re-verifies a still-desired, fail-closed revocation after its exact block is orphaned. */
@Component
class PermissionRevocationRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "PERMISSION_REVOKED";

    private final PermissionGrantRepository repository;

    PermissionRevocationRevertCompensator(PermissionGrantRepository repository) {
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
        if (grant.getStatus() != PermissionGrantStatus.REVOKED) {
            return new CompensationOutcome.NotApplicable(
                    "PermissionGrant " + id + " is no longer owned by a confirmed revocation");
        }
        if (!ChainEffectCausality.matches(effect, grant.getRevokedChainConfigId(), grant.getRevokedTx(),
                grant.getRevokedBlockNumber(), grant.getRevokedBlockHash())) {
            return new CompensationOutcome.NotApplicable(
                    "PermissionGrant " + id + " is owned by a newer revocation incarnation");
        }

        grant.setStatus(PermissionGrantStatus.REVOCATION_PENDING);
        grant.setRevokedChainConfigId(null);
        grant.setRevokedBlockNumber(null);
        grant.setRevokedBlockHash(null);
        repository.save(grant);
        return new CompensationOutcome.Compensated(
                "Returned PermissionGrant " + id + " revocation to fail-closed verification");
    }
}
