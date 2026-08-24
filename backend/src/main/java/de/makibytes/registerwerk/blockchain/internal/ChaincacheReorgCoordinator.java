package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.ReorgEnvelopeConflictException;
import de.makibytes.registerwerk.blockchain.api.ReorgProjectionPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Atomic Registerwerk application boundary for one immutable Chaincache reorg episode. */
@Service
class ChaincacheReorgCoordinator {

    private final BlockFinalityFeed finalityFeed;
    private final ReorgProjectionPort indexer;

    ChaincacheReorgCoordinator(BlockFinalityFeed finalityFeed, ReorgProjectionPort indexer) {
        this.finalityFeed = finalityFeed;
        this.indexer = indexer;
    }

    @Transactional(noRollbackFor = ReorgEnvelopeConflictException.class)
    public void apply(UUID chainConfigId, ReorgObservation observation) {
        if (finalityFeed.isReorgRecorded(chainConfigId, observation)) {
            return;
        }
        if (observation.severity() == ReorgObservation.ReorgSeverity.ROUTINE
                && finalityFeed.hasLocalFinalityConflict(chainConfigId, observation)) {
            finalityFeed.recordReorg(chainConfigId, observation, 0,
                    QuarantineTrigger.LOCAL_FINALITY_CONFLICT);
            return;
        }

        ReorgProjectionPort.AppliedReorg applied =
                observation.severity() == ReorgObservation.ReorgSeverity.ROUTINE
                        ? indexer.apply(chainConfigId, observation)
                        : new ReorgProjectionPort.AppliedReorg(0, List.of(), false);
        if (applied.blockedByLocalFinality()) {
            finalityFeed.recordReorg(chainConfigId, observation, applied.orphanedTransferCount(),
                    QuarantineTrigger.LOCAL_FINALITY_CONFLICT);
        } else {
            finalityFeed.recordReorg(chainConfigId, observation, applied.orphanedTransferCount());
        }
    }
}
