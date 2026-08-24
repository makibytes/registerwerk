package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantRepository;
import de.makibytes.registerwerk.orgidentity.api.RoleRestrictionStatus;
import org.springframework.stereotype.Component;

/** Restores the previous confirmed restriction and re-verifies the still-desired change. */
@Component
class RoleRestrictionRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ROLE_RESTRICTION_CONFIRMED";
    private final PermissionGrantRepository repository;

    RoleRestrictionRevertCompensator(PermissionGrantRepository repository) {
        this.repository = repository;
    }

    @Override public String effectType() { return EFFECT_TYPE; }
    @Override public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        var grant = repository.findById(effect.entityId()).orElse(null);
        if (grant == null) return new CompensationOutcome.NotApplicable("PermissionGrant no longer exists");
        Object before = effect.beforeState() != null ? effect.beforeState().get("roleRestricted") : null;
        Object after = effect.afterState() != null ? effect.afterState().get("roleRestricted") : null;
        if (!(before instanceof Boolean previous)) {
            return new CompensationOutcome.Failed("Restriction effect has no boolean pre-image", null);
        }
        if (!(after instanceof Boolean desired)) {
            return new CompensationOutcome.Failed("Restriction effect has no boolean post-image", null);
        }

        boolean pendingLaterIntent = grant.getRoleRestrictionStatus() == RoleRestrictionStatus.CHANGE_PENDING
                && grant.getRequestedRoleRestricted() != null
                && grant.getRoleRestrictionRequestedAt() != null
                && grant.getRoleRestrictionChainConfigId() == null
                && grant.getRoleRestrictionBlockNumber() == null
                && grant.getRoleRestrictionBlockHash() == null;
        if (grant.getRoleRestrictionStatus() == RoleRestrictionStatus.STABLE) {
            if (!ChainEffectCausality.matches(effect, grant.getRoleRestrictionChainConfigId(),
                    grant.getRoleRestrictionTx(), grant.getRoleRestrictionBlockNumber(),
                    grant.getRoleRestrictionBlockHash())) {
                return new CompensationOutcome.NotApplicable(
                        "Restriction change is owned by a different incarnation");
            }
        } else if (pendingLaterIntent) {
            if (grant.isConfirmedRoleRestricted() != desired) {
                return new CompensationOutcome.NotApplicable(
                        "Pending later restriction does not descend from this effect's post-image");
            }
        } else {
            return new CompensationOutcome.NotApplicable("Restriction change no longer owns grant state");
        }

        grant.setRoleRestricted(previous);
        if (!pendingLaterIntent) {
            grant.setRequestedRoleRestricted(desired);
            grant.setRoleRestrictionStatus(RoleRestrictionStatus.CHANGE_PENDING);
            grant.setRoleRestrictionChainConfigId(null);
            grant.setRoleRestrictionBlockNumber(null);
            grant.setRoleRestrictionBlockHash(null);
        }
        repository.save(grant);
        return new CompensationOutcome.Compensated(
                pendingLaterIntent
                        ? "Restored older pre-image while preserving the latest pending restriction intent"
                        : "Restored prior restriction and resumed verification");
    }
}
