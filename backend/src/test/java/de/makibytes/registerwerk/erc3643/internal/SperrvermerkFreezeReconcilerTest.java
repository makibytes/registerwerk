package de.makibytes.registerwerk.erc3643.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("SperrvermerkFreezeReconciler (H5)")
class SperrvermerkFreezeReconcilerTest {

    private final SperrvermerkFreezeService service = mock(SperrvermerkFreezeService.class);
    private final SperrvermerkFreezeReconciler reconciler = new SperrvermerkFreezeReconciler(service);

    @Test
    @DisplayName("the sweep and the nightly pass delegate to the service")
    void delegates() {
        reconciler.sweep();
        reconciler.reconcileNightly();

        verify(service).sweep();
        verify(service).reconcile();
    }
}
