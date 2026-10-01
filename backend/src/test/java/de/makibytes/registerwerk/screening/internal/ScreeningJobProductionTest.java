package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.shared.ProductionMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Screening refresh job and demo seeding (7A-01)")
class ScreeningJobProductionTest {

    private final ScreeningRunRepository runs = mock(ScreeningRunRepository.class);
    private final ScreeningService screening = mock(ScreeningService.class);

    @Test
    @DisplayName("demo-seeded outside production still skips (demo stack)")
    void demoSkips() {
        new ScreeningRefreshJob(runs, screening, new SimpleMeterRegistry(), true, ProductionMode.of(false)).periodicRefresh();
        verifyNoInteractions(runs);
    }

    @Test
    @DisplayName("in production the demo flag never silences the re-screening")
    void productionNeverSkips() {
        when(runs.findDistinctActiveEntityIds()).thenReturn(List.of());
        new ScreeningRefreshJob(runs, screening, new SimpleMeterRegistry(), true, ProductionMode.of(true)).periodicRefresh();
        verify(runs).findDistinctActiveEntityIds();
    }
}
