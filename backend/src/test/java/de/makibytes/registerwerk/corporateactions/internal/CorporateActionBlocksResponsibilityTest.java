package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.deployment.api.BondLifecycleTransitionEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("9A-04R: who is a past-due corporate action waiting for")
class CorporateActionBlocksResponsibilityTest {

    private static CorporateAction action(CorporateAction.Status status, boolean attested, boolean confirmed) {
        CorporateAction ca = new CorporateAction();
        ca.setStatus(status);
        if (attested) {
            ca.setIssuerAttestedAt(Instant.now());
        }
        if (confirmed) {
            ca.setDualControlApproverId(UUID.randomUUID());
        }
        return ca;
    }

    @Test
    @DisplayName("issuer attestation outstanding (or before COMPUTED) is the issuer's side")
    void issuerSide() {
        assertThat(CorporateActionBlocks.responsibleSide(action(CorporateAction.Status.COMPUTED, false, false), false).side())
                .isEqualTo(CorporateActionBlocks.Side.ISSUER);
        assertThat(CorporateActionBlocks.responsibleSide(action(CorporateAction.Status.ANNOUNCED, false, false), false)
                .countsAsIssuerNonPayment()).isTrue();
    }

    @Test
    @DisplayName("attested but not confirmed / both signed / awaiting settlement is the operator's side")
    void operatorSide() {
        var missing = CorporateActionBlocks.responsibleSide(action(CorporateAction.Status.COMPUTED, true, false), false);
        assertThat(missing.side()).isEqualTo(CorporateActionBlocks.Side.OPERATOR);
        assertThat(missing.cause()).startsWith("OPERATOR_CONFIRMATION_MISSING");
        assertThat(CorporateActionBlocks.responsibleSide(action(CorporateAction.Status.COMPUTED, true, true), false).cause())
                .startsWith("SETTLEMENT_NOT_DISPATCHED");
        assertThat(CorporateActionBlocks.responsibleSide(action(CorporateAction.Status.AWAITING_SETTLEMENT, true, true), false).side())
                .isEqualTo(CorporateActionBlocks.Side.OPERATOR);
    }

    @Test
    @DisplayName("a registry-side block wins over everything; a frozen register is registry-side")
    void registrySide() {
        assertThat(CorporateActionBlocks.responsibleSide(action(CorporateAction.Status.COMPUTED, true, true), true).side())
                .isEqualTo(CorporateActionBlocks.Side.REGISTRY);
        CorporateAction held = action(CorporateAction.Status.AWAITING_SETTLEMENT, true, true);
        held.setSettlementHoldReason("finality hold");
        assertThat(CorporateActionBlocks.responsibleSide(held, false).side()).isEqualTo(CorporateActionBlocks.Side.REGISTRY);
    }

    @Test
    @DisplayName("the bond event type is derived from the target state and carries the declaration basis")
    void eventTypeAndPayload() {
        var event = new BondLifecycleTransitionEvent(UUID.randomUUID(), BondStatus.OVERDUE, BondStatus.DEFAULTED, "why",
                List.of(UUID.randomUUID()), java.time.LocalDate.of(2025, 6, 30), 7);
        assertThat(event.eventType()).isEqualTo("BOND_DEFAULTED");
        assertThat(event.actorRole()).isEqualTo("SYSTEM");
        assertThat(event.payload()).containsEntry("from", "OVERDUE").containsEntry("to", "DEFAULTED")
                .containsEntry("graceDays", 7).containsKey("actionIds");
    }
}
