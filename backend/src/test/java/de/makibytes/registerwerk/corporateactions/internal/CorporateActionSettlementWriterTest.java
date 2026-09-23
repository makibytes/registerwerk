package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettledEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementBlockedEvent;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies : settlement confirmation previously published no audit event
 *  at all — only the settlement *request* was audited, never its confirmation. */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionSettlementWriter audit-event unit tests")
class CorporateActionSettlementWriterTest {

    @Mock private CorporateActionRepository corporateActionRepository;
    @Mock private CorporateActionEntryRepository entryRepository;
    @Mock private AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private ApplicationEventPublisher events;

    private CorporateActionSettlementWriter writer;

    @BeforeEach
    void setUp() {
        writer = new CorporateActionSettlementWriter(
                corporateActionRepository, entryRepository, couponPaymentRepository, events);
        lenient().when(entryRepository.findByCorporateActionId(any())).thenReturn(List.of());
    }

    private CorporateAction actionAwaitingSettlement(UUID id) {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", id);
        ca.setStatus(CorporateAction.Status.AWAITING_SETTLEMENT);
        when(corporateActionRepository.findById(id)).thenReturn(Optional.of(ca));
        return ca;
    }

    @Test
    @DisplayName("the automated (2-arg) path publishes a SYSTEM-attributed settlement event")
    void automatedPath_publishesSystemEvent() {
        UUID id = UUID.randomUUID();
        actionAwaitingSettlement(id);

        writer.markSettled(id, "tx-hash-1");

        ArgumentCaptor<CorporateActionSettledEvent> captor = ArgumentCaptor.forClass(CorporateActionSettledEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().corporateActionId()).isEqualTo(id);
        assertThat(captor.getValue().actorId()).isNull();
        assertThat(captor.getValue().actorRole()).isEqualTo("SYSTEM");
        assertThat(captor.getValue().txHash()).isEqualTo("tx-hash-1");
    }

    @Test
    @DisplayName("the manual (4-arg) path publishes an event carrying the real operator actor")
    void manualPath_publishesRealActorEvent() {
        UUID id = UUID.randomUUID();
        actionAwaitingSettlement(id);
        UUID actorId = UUID.randomUUID();

        writer.markSettled(id, "manual-ref", actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<CorporateActionSettledEvent> captor = ArgumentCaptor.forClass(CorporateActionSettledEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().actorRole()).isEqualTo("REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("T2-18: a HELD_LOOK_THROUGH (nominee-pool) entry is not recorded as settled")
    void heldEntry_isNotSettled() {
        UUID id = UUID.randomUUID();
        actionAwaitingSettlement(id);
        CorporateActionEntry payable = new CorporateActionEntry();
        CorporateActionEntry held = new CorporateActionEntry();
        held.setPayoutStatus(CorporateActionEntry.PayoutStatus.HELD_LOOK_THROUGH);
        when(entryRepository.findByCorporateActionId(id)).thenReturn(List.of(payable, held));

        writer.markSettled(id, "tx-hash-2");

        assertThat(payable.getSettledAt()).isNotNull();
        assertThat(held.getSettledAt()).isNull();
        assertThat(held.getSettlementTxHash()).isNull();
    }

    @Test
    @DisplayName("a disappeared CorporateAction publishes no event")
    void missingAction_publishesNothing() {
        UUID id = UUID.randomUUID();
        when(corporateActionRepository.findById(id)).thenReturn(Optional.empty());

        writer.markSettled(id, "tx-hash");

        verify(events, org.mockito.Mockito.never()).publishEvent(any());
    }

    @Test
    @DisplayName("a blocked settlement stays AWAITING_SETTLEMENT, notes the reason once and audits it once")
    void blockedSettlement_notesReasonIdempotently() {
        UUID id = UUID.randomUUID();
        CorporateAction ca = actionAwaitingSettlement(id);
        ca.setNotes("Auto-created from coupon_payment id=x");

        writer.recordSettlementBlocked(id, "rate fixing not wired");
        writer.recordSettlementBlocked(id, "rate fixing not wired");

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.AWAITING_SETTLEMENT);
        assertThat(ca.getNotes())
                .isEqualTo("Auto-created from coupon_payment id=x | settlement blocked: rate fixing not wired");
        verify(events).publishEvent(new CorporateActionSettlementBlockedEvent(id, "rate fixing not wired"));
    }
}
