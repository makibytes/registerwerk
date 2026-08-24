package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Reverts only the chain-derived revocation state owned by this exact {@code removeClaim} block
 * incarnation. The submitted revocation transaction hash deliberately remains set, keeping the
 * claim excluded while the transaction awaits a new canonical verdict; a confirmed failed receipt
 * is the only path that clears that fail-closed intent marker.
 */
@Component
class OnchainClaimRevocationRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ERC3643_CLAIM_REVOKED";

    private final OnchainClaimRepository repository;

    OnchainClaimRevocationRevertCompensator(OnchainClaimRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() {
        return EFFECT_TYPE;
    }

    @Override
    public CompensationCategory category() {
        return CompensationCategory.INVERSE_FLIP;
    }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        OnchainClaim claim = repository.findById(id).orElse(null);
        if (claim == null) {
            return new CompensationOutcome.NotApplicable("OnchainClaim " + id + " no longer exists");
        }
        if (claim.getRevokedAt() == null) {
            return new CompensationOutcome.NotApplicable("OnchainClaim " + id + " is already unrevoked");
        }
        if (!ChainEffectCausality.matches(effect, claim.getRevocationChainConfigId(),
                claim.getRevocationTxHash(), claim.getRevocationBlockNumber(), claim.getRevocationBlockHash())) {
            return new CompensationOutcome.NotApplicable(
                    "OnchainClaim " + id + " is owned by a different revocation incarnation");
        }

        claim.setRevokedAt(null);
        claim.setRevocationChainConfigId(null);
        claim.setRevocationBlockNumber(null);
        claim.setRevocationBlockHash(null);
        repository.save(claim);
        return new CompensationOutcome.Compensated(
                "Reopened OnchainClaim " + id + " after revocation-block retraction");
    }
}
