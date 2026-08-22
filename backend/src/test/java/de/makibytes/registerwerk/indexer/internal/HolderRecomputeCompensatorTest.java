package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.indexer.api.HolderDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("HolderRecomputeCompensator — the RECOMPUTE compensator for asset_holder")
class HolderRecomputeCompensatorTest {

    @Mock private HolderDataService holderDataService;

    private HolderRecomputeCompensator compensator;

    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new HolderRecomputeCompensator(holderDataService);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", null, null,
                "indexer", "HOLDER_BALANCE_SYNCED", "Asset", assetId, null, CompensationCategory.RECOMPUTE,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    @DisplayName("advertises effectType HOLDER_BALANCE_SYNCED and category RECOMPUTE")
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("HOLDER_BALANCE_SYNCED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.RECOMPUTE);
    }

    @Test
    @DisplayName("compensating re-runs syncHoldersFromBlockchain for the affected asset and reports Compensated")
    void compensateReSyncsHolders() {
        CompensationOutcome outcome = compensator.compensate(effect());

        verify(holderDataService).syncHoldersFromBlockchain(assetId);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    @DisplayName("a recompute failure is reported as Failed, not thrown")
    void recomputeFailureIsReportedAsFailed() {
        doThrow(new RuntimeException("db down")).when(holderDataService).syncHoldersFromBlockchain(assetId);

        CompensationOutcome outcome = compensator.compensate(effect());

        assertThat(outcome).isInstanceOf(CompensationOutcome.Failed.class);
    }
}
