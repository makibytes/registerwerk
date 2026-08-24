package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityNotReachedException;
import de.makibytes.registerwerk.finality.api.FinalityPolicyService;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Default {@link FinalityGate}: resolves the required {@link FinalityLevel} for a
 * {@link GatedOperation} via {@link FinalityPolicyService} and compares it against the caller's
 * {@code currentLevel}, blocking with a specific {@link FinalityDecision.Blocked.Reason} when it
 * isn't met.
 *
 * <p>Also enforces a per-asset freeze: while {@code assetId} has any {@link ChainEffect.Status#COMPENSATION_FAILED}
 * or {@link ChainEffect.Status#IRREVERSIBLE_ESCALATED} row that no admin has acknowledged, every
 * operation on that asset is blocked with {@link FinalityDecision.Blocked.Reason#UNRESOLVED_COMPENSATION}
 * — regardless of {@code currentLevel}, since the whole point is that a dashboard nobody watches
 * is not fail-closed. See {@code finality.web.FinalityJournalController} for the operator queue
 * that lists and acknowledges these.
 *
 * <p>A persisted chain quarantine is stricter still: every asset deployed on, or carrying effect
 * provenance from, that chain is blocked with {@link FinalityDecision.Blocked.Reason#CHAIN_QUARANTINED}
 * until an explicit operator-resolution workflow clears the active incident.
 */
@Component
class FinalityGateImpl implements FinalityGate {

    private static final List<ChainEffect.Status> UNRESOLVED_STATUSES =
            List.of(ChainEffect.Status.COMPENSATION_FAILED, ChainEffect.Status.IRREVERSIBLE_ESCALATED);

    private final FinalityPolicyService policyService;
    private final ChainEffectRepository chainEffectRepository;
    private final ChainQuarantineStore chainQuarantineStore;

    FinalityGateImpl(FinalityPolicyService policyService, ChainEffectRepository chainEffectRepository,
            ChainQuarantineStore chainQuarantineStore) {
        this.policyService = policyService;
        this.chainEffectRepository = chainEffectRepository;
        this.chainQuarantineStore = chainQuarantineStore;
    }

    @Override
    public FinalityDecision check(GatedOperation operation, UUID assetId, TokenStandard tokenStandard, FinalityLevel currentLevel) {
        FinalityLevel required = policyService.requiredLevel(operation, assetId, tokenStandard);

        if (chainQuarantineStore.isAssetAffected(assetId)) {
            return new FinalityDecision.Blocked(operation, assetId, required, currentLevel,
                    FinalityDecision.Blocked.Reason.CHAIN_QUARANTINED,
                    "A chain used by this asset is quarantined after a finality safety incident; "
                            + "irreversible operations remain frozen pending explicit operator resolution.");
        }
        if (currentLevel == FinalityLevel.ORPHANED) {
            return new FinalityDecision.Blocked(operation, assetId, required, currentLevel,
                    FinalityDecision.Blocked.Reason.ORPHANED,
                    "The on-chain event this depends on was reorged out and is no longer valid.");
        }
        if (assetId != null && chainEffectRepository.existsByAssetIdAndStatusInAndAcknowledgedAtIsNull(assetId, UNRESOLVED_STATUSES)) {
            return new FinalityDecision.Blocked(operation, assetId, required, currentLevel,
                    FinalityDecision.Blocked.Reason.UNRESOLVED_COMPENSATION,
                    "A reorg-caused compensation for this asset failed or could not be automatically "
                            + "undone and has not yet been acknowledged by an administrator.");
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
