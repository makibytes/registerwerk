package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.repo.api.RepoDeskParticipantRepository;
import de.makibytes.registerwerk.repo.api.RepoSubstitutionRequestRepository;
import de.makibytes.registerwerk.repo.api.RepoTradeRepository;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** H11: the double-pledge check must run under the same advisory lock the trading module takes. */
@ExtendWith(MockitoExtension.class)
class RepoControlsTest {
    @Mock PartyEligibilityGate gate;
    @Mock LegalEntityRepository entities;
    @Mock RepoDeskParticipantRepository participants;
    @Mock AssetHolderRepository holders;
    @Mock TradeListingRepository listings;
    @Mock TradeExecutionRepository executions;
    @Mock RepoTradeRepository trades;
    @Mock RepoSubstitutionRequestRepository substitutions;
    @Mock AssetBondTermsRepository bondTerms;
    @Mock CorporateActionRepository corporateActions;
    @Mock EntityTaskPort entityTasks;

    RepoControls controls;
    final UUID borrower = UUID.randomUUID();
    final UUID asset = UUID.randomUUID();

    @BeforeEach void setUp() {
        controls = new RepoControls(gate, entities, participants, holders, listings, executions, trades,
                substitutions, bondTerms, corporateActions, entityTasks);
        lenient().when(holders.sumActiveNominalByInvestorIdAndAssetId(borrower, asset)).thenReturn(new BigDecimal("100"));
        lenient().when(holders.findActiveByInvestorId(borrower)).thenReturn(List.of());
        lenient().when(trades.sumPledged(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(substitutions.sumApprovedReplacement(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test void requireHoldingTakesTheSharedHoldingLockBeforeReadingTheCommittedQuantities() {
        controls.requireHolding(borrower, asset, new BigDecimal("10"));

        InOrder order = inOrder(executions, holders, trades);
        order.verify(executions).lockHolding(borrower, asset);
        order.verify(holders).sumActiveNominalByInvestorIdAndAssetId(borrower, asset);
        order.verify(trades).sumPledged(any(), any(), any());
    }

    @Test void requireHoldingStillRefusesWhenTheUnitsAreNotAvailable() {
        assertThatThrownBy(() -> controls.requireHolding(borrower, asset, new BigDecimal("101")))
                .isInstanceOf(ComplianceGateException.class);
    }

    // ── 9A-08: protective actions refuse only on hard stops ──

    private LegalEntity creditor() {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setClientCategory(ClientCategory.PROFESSIONAL);
        return e;
    }

    @Test void protectiveActorWithOnlySoftReasonsIsAllowedAndTheReasonsAreReturned() {
        LegalEntity lender = creditor();
        when(entities.findById(lender.getId())).thenReturn(java.util.Optional.of(lender));
        when(gate.hardStops(lender.getId())).thenReturn(List.of());
        when(gate.check(lender.getId(), null)).thenReturn(List.of("has an expired KYC (expired 2026-01-01)"));
        org.assertj.core.api.Assertions.assertThat(controls.requireProtectiveActor(lender, "a margin call"))
                .containsExactly("has an expired KYC (expired 2026-01-01)");
    }

    @Test void protectiveActorWithAHardStopIsRefusedWithoutLookingAtTheSoftReasons() {
        LegalEntity lender = creditor();
        when(gate.hardStops(lender.getId())).thenReturn(List.of("has an unresolved sanctions-screening result"));
        assertThatThrownBy(() -> controls.requireProtectiveActor(lender, "a default notice"))
                .isInstanceOf(ComplianceGateException.class).hasMessageContaining("sanctions-screening");
    }

    @Test void enforcementTaskIsRaisedOnTheEnforcingEntityForTheTrade() {
        UUID trade = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        controls.openEnforcementTask(borrower, trade, "a margin call", List.of("has an expired KYC"), actor);
        org.mockito.Mockito.verify(entityTasks).open(org.mockito.ArgumentMatchers.eq(borrower),
                org.mockito.ArgumentMatchers.eq("REPO_PARTY_INELIGIBLE_ENFORCEMENT"),
                org.mockito.ArgumentMatchers.eq(trade.toString()), org.mockito.ArgumentMatchers.contains("expired KYC"),
                org.mockito.ArgumentMatchers.eq(actor));
    }
}
