package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code VAULT_REQUEST_RESOLVED} — undoes a {@link VaultRequest}
 * whose confirming {@code fulfillDepositRequest}/{@code fulfillRedeemRequest}/{@code
 * cancelDepositRequest}/{@code cancelRedeemRequest} block was later retracted. Handles both
 * outcomes with one compensator (rather than one per action, unlike the erc3643 identity-registry
 * pair) because a {@link VaultRequest} can only ever be in one terminal state at a time — the
 * branch below just asks which one it currently is and undoes that.
 *
 * <p>Reverts {@code requestStatus} back to {@code PENDING} and clears whichever tx column drove
 * the resolution, so the request becomes eligible for {@code Erc7540AdminService} to resubmit
 * fulfilment/cancellation — the same "let a human/retry path decide what happens next" stance used
 * throughout this reorg-compensation work.
 *
 * <p>Talks to {@link VaultRequestRepository} directly, never {@code VaultConfirmationListener} or
 * {@code Erc7540AdminService} (both would introduce the circular Spring-bean dependency described
 * in {@code blockchain.internal.tx.BlockchainTxRevertCompensator}'s javadoc).
 */
@Component
class VaultRequestFulfillmentRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "VAULT_REQUEST_RESOLVED";

    private static final Logger log = LoggerFactory.getLogger(VaultRequestFulfillmentRevertCompensator.class);

    private final VaultRequestRepository vaultRequestRepository;

    VaultRequestFulfillmentRevertCompensator(VaultRequestRepository vaultRequestRepository) {
        this.vaultRequestRepository = vaultRequestRepository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        VaultRequest request = vaultRequestRepository.findById(id).orElse(null);
        if (request == null) {
            return new CompensationOutcome.NotApplicable("VaultRequest " + id + " no longer exists");
        }
        if (request.getRequestStatus() == VaultRequestStatus.PENDING) {
            return new CompensationOutcome.NotApplicable(
                    "VaultRequest " + id + " is already PENDING (already reverted, or never resolved)");
        }

        log.error("VaultRequest id={} was marked {} but its confirming block was retracted by a reorg deep "
                        + "enough to cross FINALIZED — the fulfil/cancel never actually happened on-chain; "
                        + "reverting to PENDING so it can be resubmitted.",
                id, request.getRequestStatus());

        if (request.getRequestStatus() == VaultRequestStatus.FULFILLED) {
            request.setFulfilledTx(null);
            request.setFulfilledAt(null);
            request.setNavAtFulfill(null);
        } else {
            request.setCancelledTx(null);
        }
        request.setRequestStatus(VaultRequestStatus.PENDING);
        request.setConfirmed(false);
        request.setChainConfigId(null);
        request.setBlockNumber(null);
        vaultRequestRepository.save(request);

        return new CompensationOutcome.Compensated("Reverted VaultRequest " + id + " to PENDING after retraction");
    }
}
