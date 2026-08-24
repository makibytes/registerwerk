package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.blockchain.api.ReorgProjectionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChaincacheReorgCoordinatorTest {

    @Mock BlockFinalityFeed finalityFeed;
    @Mock ReorgProjectionPort indexer;

    @Test
    void localSettledConflictIsQuarantinedBeforeAnyIndexerMutation() {
        UUID chainId = UUID.randomUUID();
        ReorgObservation observation = routine();
        when(finalityFeed.hasLocalFinalityConflict(chainId, observation)).thenReturn(true);

        new ChaincacheReorgCoordinator(finalityFeed, indexer).apply(chainId, observation);

        verify(indexer, never()).apply(chainId, observation);
        verify(finalityFeed).recordReorg(
                chainId, observation, 0, QuarantineTrigger.LOCAL_FINALITY_CONFLICT);
    }

    @Test
    void safeRoutineEpisodePreflightsThenMutatesThenClaimsInOneCoordinatorCall() {
        UUID chainId = UUID.randomUUID();
        ReorgObservation observation = routine();
        when(indexer.apply(chainId, observation))
                .thenReturn(new ReorgProjectionPort.AppliedReorg(2, List.of()));

        new ChaincacheReorgCoordinator(finalityFeed, indexer).apply(chainId, observation);

        InOrder order = inOrder(finalityFeed, indexer);
        order.verify(finalityFeed).isReorgRecorded(chainId, observation);
        order.verify(finalityFeed).hasLocalFinalityConflict(chainId, observation);
        order.verify(indexer).apply(chainId, observation);
        order.verify(finalityFeed).recordReorg(chainId, observation, 2);
    }

    private static ReorgObservation routine() {
        var ancestor = new ReorgObservation.BlockReference(99, "0x99", "0x98", FinalityLevel.SAFE);
        return new ReorgObservation("1", UUID.randomUUID().toString(),
                ReorgObservation.ReorgSeverity.ROUTINE, ancestor,
                List.of(new ReorgObservation.BlockReference(100, "0xaaa", "0x99", FinalityLevel.SAFE)),
                List.of(new ReorgObservation.BlockReference(100, "0xbbb", "0x99", FinalityLevel.PROVISIONAL)),
                Instant.now());
    }
}
