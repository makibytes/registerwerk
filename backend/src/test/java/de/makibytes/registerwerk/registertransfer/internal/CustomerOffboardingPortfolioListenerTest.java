package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerOffboardingPortfolioListener unit tests")
class CustomerOffboardingPortfolioListenerTest {

    @Mock
    private AssetHolderRepository holderRepository;

    @Mock
    private PortfolioMigrationService migrationService;

    private CustomerOffboardingPortfolioListener listener;

    @BeforeEach
    void setUp() {
        listener = new CustomerOffboardingPortfolioListener(holderRepository, migrationService);
    }

    private static AssetHolder holder(UUID id, BigDecimal nominalAmount) {
        AssetHolder h = new AssetHolder();
        ReflectionTestUtils.setField(h, "id", id);
        h.setNominalAmount(nominalAmount);
        return h;
    }

    @Test
    @DisplayName("no holdings — nothing raised")
    void noHoldings_raisesNothing() {
        UUID entityId = UUID.randomUUID();
        when(holderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of());

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "terminated"));

        verify(migrationService, never()).initiate(any(), anyString(), any());
    }

    @Test
    @DisplayName("holdings with a zero or null nominal amount are skipped")
    void zeroOrNullNominalAmount_skipped() {
        UUID entityId = UUID.randomUUID();
        AssetHolder zero = holder(UUID.randomUUID(), BigDecimal.ZERO);
        AssetHolder nullAmount = holder(UUID.randomUUID(), null);
        when(holderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of(zero, nullAmount));

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "terminated"));

        verify(migrationService, never()).initiate(any(), anyString(), any());
    }

    @Test
    @DisplayName("raises a migration request for every holding with a positive nominal amount")
    void positiveHoldings_raisesMigrationPerHolding() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AssetHolder first = holder(UUID.randomUUID(), new BigDecimal("10"));
        AssetHolder second = holder(UUID.randomUUID(), new BigDecimal("5"));
        when(holderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of(first, second));

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, actorId, "REGISTRY_ADMIN", "contract terminated"));

        verify(migrationService).initiate(eq(first.getId()), eq("Customer offboarded: contract terminated"), eq(actorId));
        verify(migrationService).initiate(eq(second.getId()), eq("Customer offboarded: contract terminated"), eq(actorId));
        verify(migrationService, times(2)).initiate(any(), anyString(), any());
    }

    @Test
    @DisplayName("a holding already mid-migration is skipped without failing the rest")
    void alreadyInProgress_doesNotPropagate() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AssetHolder alreadyMigrating = holder(UUID.randomUUID(), new BigDecimal("10"));
        AssetHolder normal = holder(UUID.randomUUID(), new BigDecimal("5"));
        when(holderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of(alreadyMigrating, normal));
        when(migrationService.initiate(eq(alreadyMigrating.getId()), anyString(), any()))
                .thenThrow(new IllegalStateException("already in progress"));

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, actorId, "REGISTRY_ADMIN", "terminated"));

        verify(migrationService).initiate(eq(normal.getId()), anyString(), eq(actorId));
    }

    @Test
    @DisplayName("a legally blocked holding's migration failure does not propagate and does not skip the rest "
            + "(ComplianceGateException must not be swallowed as a benign 'already in progress' duplicate)")
    void blockedHolder_doesNotPropagateAndIsNotMisreportedAsDuplicate() {
        UUID entityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AssetHolder blocked = holder(UUID.randomUUID(), new BigDecimal("10"));
        AssetHolder normal = holder(UUID.randomUUID(), new BigDecimal("5"));
        when(holderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of(blocked, normal));
        when(migrationService.initiate(eq(blocked.getId()), anyString(), any()))
                .thenThrow(new de.makibytes.registerwerk.shared.ComplianceGateException("Sperrvermerk active"));

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, actorId, "REGISTRY_ADMIN", "terminated"));

        verify(migrationService).initiate(eq(normal.getId()), anyString(), eq(actorId));
    }
}
