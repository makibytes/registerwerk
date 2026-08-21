package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityNotReachedException;
import de.makibytes.registerwerk.finality.api.FinalityPolicyService;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class FinalityGateImpl implements FinalityGate {

    private final FinalityPolicyService policyService;

    FinalityGateImpl(FinalityPolicyService policyService) {
        this.policyService = policyService;
    }

    @Override
    public FinalityDecision check(GatedOperation operation, UUID assetId, TokenStandard tokenStandard, FinalityLevel currentLevel) {
        FinalityLevel required = policyService.requiredLevel(operation, assetId, tokenStandard);

        if (currentLevel == FinalityLevel.ORPHANED) {
            return new FinalityDecision.Blocked(operation, assetId, required, currentLevel,
                    FinalityDecision.Blocked.Reason.ORPHANED,
                    "The on-chain event this depends on was reorged out and is no longer valid.");
        }
        if (currentLevel.atLeast(required)) {
            return new FinalityDecision.Allowed(currentLevel);
        }
        return new FinalityDecision.Blocked(operation, assetId, required, currentLevel,
                FinalityDecision.Blocked.Reason.BELOW_REQUIRED,
                "Requires " + required + ", currently " + currentLevel + ".");
    }

    @Override
    public void require(GatedOperation operation, UUID assetId, TokenStandard tokenStandard, FinalityLevel currentLevel) {
        if (check(operation, assetId, tokenStandard, currentLevel) instanceof FinalityDecision.Blocked blocked) {
            throw new FinalityNotReachedException(blocked);
        }
    }
}
