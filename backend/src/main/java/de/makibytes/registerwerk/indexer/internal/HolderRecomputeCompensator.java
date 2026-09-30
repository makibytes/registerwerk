package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.indexer.api.HolderDataService;
import de.makibytes.registerwerk.indexer.api.UnmappedHolderIdentityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The RECOMPUTE compensator for {@code asset_holder} balances. Registered against effect type
 * {@link #EFFECT_TYPE}, discovered by {@code CompensationDispatcher} via
 * {@link ChainEffectCompensator} collection injection (see its javadoc for why this needs no
 * import of {@code finality.internal}).
 *
 * <p>Deliberately trivial: {@code asset_holder} is a pure aggregate over still-current, FINALIZED
 * {@code token_transfer} rows (see {@code HolderDataService}'s javadoc), so "undo the effect of a
 * retracted block" and "recompute from scratch" are the same operation — there is nothing to
 * restore from a snapshot. {@link ChainEffectRecord#beforeState()}/{@code afterState()} are
 * intentionally unused here.
 */
@Component
class HolderRecomputeCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "HOLDER_BALANCE_SYNCED";

    private static final Logger log = LoggerFactory.getLogger(HolderRecomputeCompensator.class);

    private final HolderDataService holderDataService;

    HolderRecomputeCompensator(HolderDataService holderDataService) {
        this.holderDataService = holderDataService;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.RECOMPUTE; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        try {
            holderDataService.syncHoldersFromBlockchain(effect.entityId());
            return new CompensationOutcome.Compensated(
                    "Holder balances recomputed for asset " + effect.entityId()
                            + " after reorg at chainConfigId=" + effect.chainConfigId()
                            + " block=" + effect.blockNumber());
        } catch (UnmappedHolderIdentityException e) {
            // P4-07: a refused recompute (unmapped identity, closed entry, missing amount, negative
            // balance, deployment not indexed) has already persisted the BLOCKED marker
            // (noRollbackFor). The register is flagged for the operator; failing here would roll
            // back the orphaning and cursor rewind and loop the typed-reorg episode forever.
            log.warn("HolderRecomputeCompensator: register BLOCKED for asset={}: {}",
                    effect.entityId(), e.getMessage());
            return new CompensationOutcome.Compensated(
                    "holder balances not recomputed: register BLOCKED (" + e.getMessage()
                            + "); orphaned rows already excluded");
        } catch (Exception e) {
            log.warn("HolderRecomputeCompensator: recompute failed for asset={}: {}",
                    effect.entityId(), e.getMessage(), e);
            return new CompensationOutcome.Failed("Holder recompute failed: " + e.getMessage(), e);
        }
    }
}
