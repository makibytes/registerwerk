package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.finality.api.BlockIdentity;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code VAULT_REQUEST_INGESTED} — a {@link VaultRequest} row
 * {@link VaultRequestIngestionService} created from a {@code DepositRequested}/{@code
 * RedeemRequested} log whose block was later retracted: the request never existed on the
 * canonical chain, so the row is removed.
 *
 * <p>Only an untouched row (still PENDING, no fulfil/cancel submitted) is removed. A row that has
 * already been acted on is reported {@link CompensationOutcome.Failed} so the retry job comes
 * back once that action's own {@code VAULT_REQUEST_RESOLVED} compensation has reverted it.
 */
@Component
class VaultRequestIngestRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "VAULT_REQUEST_INGESTED";

    private static final Logger log = LoggerFactory.getLogger(VaultRequestIngestRevertCompensator.class);

    private final VaultRequestRepository vaultRequestRepository;

    VaultRequestIngestRevertCompensator(VaultRequestRepository vaultRequestRepository) {
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
        if (!BlockIdentity.sameIncarnation(request.getRequestedBlockNumber(), request.getRequestedBlockHash(),
                        effect.blockNumber(), effect.blockHash())
                || !BlockIdentity.sameHash(effect.txHash(), request.getRequestedTx())) {
            return new CompensationOutcome.NotApplicable(
                    "VaultRequest " + id + " now reflects a different request occurrence");
        }
        if (request.getRequestStatus() != VaultRequestStatus.PENDING
                || request.getFulfilledTx() != null || request.getCancelledTx() != null) {
            return new CompensationOutcome.Failed("VaultRequest " + id + " was already acted on ("
                    + request.getRequestStatus() + "); retry once that resolution is compensated", null);
        }
        log.error("VaultRequest id={} (request={}) was ingested from a block that was retracted by a reorg "
                + "— the request never happened on-chain; removing it.", id, request.getRequestId());
        vaultRequestRepository.delete(request);
        return new CompensationOutcome.Compensated("Removed VaultRequest " + id + " after retraction");
    }
}
