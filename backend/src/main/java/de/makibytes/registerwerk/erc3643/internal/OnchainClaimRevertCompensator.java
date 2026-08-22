package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.OnchainClaim;
import de.makibytes.registerwerk.erc3643.api.OnchainClaimRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code ERC3643_CLAIM_CONFIRMED} — undoes an
 * {@link OnchainClaim} whose confirming {@code addClaim} block was later retracted by a reorg.
 * Sets {@code confirmed=false} (immediately excluding the claim from
 * {@code ClaimIssuanceService#getActiveClaims} again — the claim's underlying compliance signal is
 * treated as never having been proven) and does <b>not</b> attempt to auto-resubmit the claim:
 * matches this plan's established pattern of reverting to the last-known-safe state and letting a
 * human/retry path decide what happens next.
 *
 * <p>The claim row itself is not deleted — its existence, even unconfirmed, is part of the
 * eWpG-style audit trail of "this claim was attempted".
 *
 * <p>Talks to {@link OnchainClaimRepository} directly, never {@code Erc3643ClaimConfirmationListener}
 * or {@code Erc3643DeploymentService}/{@code ClaimIssuanceService} (all would introduce the
 * circular Spring-bean dependency described in
 * {@code blockchain.internal.tx.BlockchainTxRevertCompensator}'s javadoc).
 */
@Component
class OnchainClaimRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "ERC3643_CLAIM_CONFIRMED";

    private static final Logger log = LoggerFactory.getLogger(OnchainClaimRevertCompensator.class);

    private final OnchainClaimRepository claimRepository;

    OnchainClaimRevertCompensator(OnchainClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        OnchainClaim claim = claimRepository.findById(id).orElse(null);
        if (claim == null) {
            return new CompensationOutcome.NotApplicable("OnchainClaim " + id + " no longer exists");
        }
        if (!claim.isConfirmed()) {
            return new CompensationOutcome.NotApplicable(
                    "OnchainClaim " + id + " is already unconfirmed (already reverted, or never confirmed)");
        }

        log.error("OnchainClaim id={} topic={} was confirmed but its confirming block was retracted by a "
                        + "reorg — the claim was never actually added on-chain; "
                        + "reverting to unconfirmed (excluded from compliance checks).",
                id, claim.getTopic());
        claim.setConfirmed(false);
        claim.setChainConfigId(null);
        claim.setBlockNumber(null);
        claimRepository.save(claim);

        return new CompensationOutcome.Compensated("Reverted OnchainClaim " + id + " to unconfirmed after retraction");
    }
}
