package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoTradeServiceTest {
    @Mock RepoTradeRepository trades; @Mock RepoLifecycleEventRepository events;
    @Mock LegalEntityRepository entities; @Mock AssetRepository assets;
    @Mock RepoSubstitutionRequestRepository substitutions; @Mock RepoControls controls;
    @Mock org.springframework.context.ApplicationEventPublisher publisher;
    RepoTradeService service; RepoTrade trade; UUID borrower=UUID.randomUUID(),lender=UUID.randomUUID(); UUID user=UUID.randomUUID();

    @BeforeEach void setUp(){RepoDeskProperties p=new RepoDeskProperties();p.setEnabled(true);p.setReleaseApproved(true);
        service=new RepoTradeService(p,trades,events,entities,assets,substitutions,controls,publisher);trade=new RepoTrade();trade.setId(UUID.randomUUID());
        trade.setCashBorrowerEntityId(borrower);trade.setCashLenderEntityId(lender);trade.setCollateralAssetId(UUID.randomUUID());
        trade.setCollateralQuantity(new BigDecimal("100"));trade.setCashAmount(new BigDecimal("90000"));
        trade.setCashCurrency("EUR");trade.setHaircutBps(500);
        trade.setRepurchaseAmount(new BigDecimal("90500"));
        // RepoTradeService checks LocalDate.now(ZoneOffset.UTC) against the dates - never the system zone.
        trade.setStartDate(LocalDate.now(ZoneOffset.UTC));trade.setEndDate(LocalDate.now(ZoneOffset.UTC).plusDays(30));
        lenient().when(trades.findByIdForUpdate(trade.getId())).thenReturn(Optional.of(trade));lenient().when(entities.existsById(any())).thenReturn(true);
        lenient().when(entities.findById(any())).thenAnswer(i->Optional.of(entity(i.getArgument(0))));
        lenient().when(assets.findById(trade.getCollateralAssetId())).thenReturn(Optional.of(asset()));
        lenient().when(events.findByRepoTradeIdOrderByCreatedAtAsc(trade.getId())).thenReturn(List.of());
        lenient().when(substitutions.findByRepoTradeIdOrderByRequestedAtAsc(any())).thenReturn(List.of());
        lenient().when(substitutions.findFirstByRepoTradeIdAndStatusIn(any(),any())).thenReturn(Optional.empty());
        lenient().when(substitutions.findByRepoTradeIdAndStatusIn(any(),any())).thenReturn(List.of());}

    @Test void opensOnlyAfterBothRecipientsConfirmAndDuplicateConfirmationIsIdempotent(){
        service.confirmOpenLeg(trade.getId(),borrower,user,RepoTradeService.SettlementLeg.CASH,"cash-1");
        assertThat(trade.isOpenCashConfirmed()).isTrue();assertThat(trade.getStatus()).isEqualTo(TradeStatus.PENDING_OPEN_SETTLEMENT);
        service.confirmOpenLeg(trade.getId(),lender,user,RepoTradeService.SettlementLeg.COLLATERAL,"sec-1");
        assertThat(trade.isOpenCollateralConfirmed()).isTrue();assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        service.confirmOpenLeg(trade.getId(),borrower,user,RepoTradeService.SettlementLeg.CASH,"cash-1");
        verify(events,times(3)).save(any());
    }

    @Test void rejectsInvalidAmountsEvenWhenCalledOutsideTheWebLayer(){
        trade.setStatus(TradeStatus.OPEN);
        assertThatThrownBy(() -> service.issueMarginCall(trade.getId(), lender, user,
                BigDecimal.ZERO, Instant.now().plusSeconds(200_000), "val-1", new BigDecimal("80000"), "call"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> service.requestSubstitution(trade.getId(), borrower, user,
                UUID.randomUUID(), new BigDecimal("-1"), "replacement"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("greater than zero");
        verifyNoInteractions(events);
    }

    // ── margin ──

    @Test void marginCallNeedsCurePeriodValuationAndArithmeticConsistency(){
        trade.setStatus(TradeStatus.OPEN);
        // collateral worth 80,000 at 5% haircut = 76,000 against 90,500 repurchase -> shortfall 14,500
        assertThatThrownBy(() -> service.issueMarginCall(trade.getId(), lender, user, new BigDecimal("10"),
                Instant.now().plusSeconds(1), "val", new BigDecimal("80000"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hours to cure");
        Instant due = Instant.now().plus(2, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.issueMarginCall(trade.getId(), lender, user, new BigDecimal("14500.01"),
                due, "val", new BigDecimal("80000"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds the shortfall");
        assertThatThrownBy(() -> service.issueMarginCall(trade.getId(), lender, user, new BigDecimal("10"),
                due, "val", new BigDecimal("200000"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no shortfall");
        service.issueMarginCall(trade.getId(), lender, user, new BigDecimal("14500"), due, "val", new BigDecimal("80000"), null);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.MARGIN_CALL);
        assertThat(trade.getMarginHaircutBps()).isEqualTo(500);
    }

    @Test void borrowerCannotSelfClearMarginCallOnlyLenderConfirmationDoes(){
        trade.setStatus(TradeStatus.MARGIN_CALL);trade.setMarginCallAmount(new BigDecimal("100"));trade.setMarginCallDueAt(Instant.now().minusSeconds(60));
        service.declareMarginDelivered(trade.getId(), borrower, user, "wire-9", null);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.MARGIN_CALL);
        assertThat(trade.getMarginDeliveredAt()).isNotNull();
        assertThatThrownBy(() -> service.confirmMarginReceived(trade.getId(), borrower, user, "x", null)).isInstanceOf(AccessDeniedException.class);
        service.confirmMarginReceived(trade.getId(), lender, user, "bank-stmt", null);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(trade.getMarginCallAmount()).isNull();
    }

    // ── default ──

    @Test void marginDefaultNeedsNoticeThenGraceAndIsBlockedByTimelyBorrowerDeclaration(){
        trade.setStatus(TradeStatus.MARGIN_CALL);trade.setMarginCallAmount(new BigDecimal("100"));
        trade.setMarginCallDueAt(Instant.now().minusSeconds(3600));
        assertThatThrownBy(() -> service.declareDefault(trade.getId(), lender, user, "n")).hasMessageContaining("notice must be served first");
        service.serveDefaultNotice(trade.getId(), lender, user, null);
        assertThatThrownBy(() -> service.declareDefault(trade.getId(), lender, user, "n")).hasMessageContaining("grace period");
        trade.setDefaultNoticeAt(Instant.now().minus(25, ChronoUnit.HOURS));
        service.declareDefault(trade.getId(), lender, user, "n");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.DEFAULTED);
        assertThat(trade.getDefaultingPartyEntityId()).isEqualTo(borrower);
    }

    @Test void timelyMarginDeclarationBlocksDefaultNotice(){
        trade.setStatus(TradeStatus.MARGIN_CALL);trade.setMarginCallDueAt(Instant.now().minusSeconds(3600));
        trade.setMarginDeliveredAt(Instant.now().minusSeconds(7200));
        assertThatThrownBy(() -> service.serveDefaultNotice(trade.getId(), lender, user, null)).hasMessageContaining("declared performance");
    }

    @Test void lenderCannotDeclareDefaultWhenCashWasConfirmedAndCollateralIsMissing(){
        trade.setStatus(TradeStatus.PENDING_CLOSE);trade.setEndDate(LocalDate.now(ZoneOffset.UTC).minusDays(2));
        trade.setCloseCashConfirmed(true);trade.setDefaultNoticeAt(Instant.now().minus(48,ChronoUnit.HOURS));
        trade.setDefaultNoticeGround(DefaultGround.COLLATERAL_RETURN_FAILURE);
        assertThatThrownBy(() -> service.declareDefault(trade.getId(), lender, user, "n")).isInstanceOf(AccessDeniedException.class);
        service.declareDefault(trade.getId(), borrower, user, "collateral never returned");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.DEFAULTED);
        assertThat(trade.getDefaultGround()).isEqualTo(DefaultGround.COLLATERAL_RETURN_FAILURE);
        assertThat(trade.getDefaultingPartyEntityId()).isEqualTo(lender);
    }

    @Test void unrebuttedBorrowerPaymentDeclarationBlocksRepurchaseDefault(){
        trade.setStatus(TradeStatus.PENDING_CLOSE);trade.setEndDate(LocalDate.now(ZoneOffset.UTC).minusDays(2));
        trade.setCloseCashDeclaredAt(Instant.now().minusSeconds(100_000));
        assertThatThrownBy(() -> service.serveDefaultNotice(trade.getId(), lender, user, null)).hasMessageContaining("declared performance");
        trade.setCloseCashDeclaredAt(null);
        service.serveDefaultNotice(trade.getId(), lender, user, null);
        assertThat(trade.getDefaultNoticeGround()).isEqualTo(DefaultGround.REPURCHASE_UNPAID);
    }

    @Test void repurchaseNotYetOverdueGroundsNoDefault(){
        trade.setStatus(TradeStatus.PENDING_CLOSE);trade.setEndDate(LocalDate.now(ZoneOffset.UTC));
        assertThatThrownBy(() -> service.serveDefaultNotice(trade.getId(), lender, user, null)).hasMessageContaining("No overdue obligation");
    }

    // ── substitution / dispute ──

    @Test void substitutionIsRefusedOnClosedOrDefaultedTrades(){
        for (TradeStatus status : List.of(TradeStatus.CLOSED, TradeStatus.DEFAULTED)) {
            trade.setStatus(status);
            assertThatThrownBy(() -> service.requestSubstitution(trade.getId(), borrower, user, UUID.randomUUID(), BigDecimal.ONE, null))
                    .hasMessageContaining("not open");
            assertThatThrownBy(() -> service.decideSubstitution(trade.getId(), UUID.randomUUID(), lender, user, true, null))
                    .hasMessageContaining("not open");
        }
    }

    @Test void approvedSubstitutionSwapsCollateralOnlyWhenBothLegsAreConfirmed(){
        trade.setStatus(TradeStatus.OPEN);
        UUID newAsset=UUID.randomUUID(); UUID original=trade.getCollateralAssetId();
        RepoSubstitutionRequest request=new RepoSubstitutionRequest(); request.setRepoTradeId(trade.getId());
        request.setAssetId(newAsset); request.setQuantity(new BigDecimal("7")); request.setStatus(SubstitutionStatus.APPROVED);
        lenient().when(substitutions.findFirstByRepoTradeIdAndStatusIn(any(),any())).thenReturn(Optional.of(request));
        lenient().when(assets.findById(newAsset)).thenReturn(Optional.of(asset()));
        service.confirmSubstitutionLeg(trade.getId(), lender, user, RepoTradeService.SubstitutionLeg.REPLACEMENT_IN, "in-1");
        assertThat(trade.getCollateralAssetId()).isEqualTo(original);
        service.confirmSubstitutionLeg(trade.getId(), borrower, user, RepoTradeService.SubstitutionLeg.ORIGINAL_OUT, "out-1");
        assertThat(trade.getCollateralAssetId()).isEqualTo(newAsset);
        assertThat(request.getStatus()).isEqualTo(SubstitutionStatus.COMPLETED);
    }

    @Test void disputeFreezesTradeAndOperatorResumesIt(){
        trade.setStatus(TradeStatus.PENDING_CLOSE);
        service.openDispute(trade.getId(), borrower, user, "cash was wired");
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertThatThrownBy(() -> service.declareDefault(trade.getId(), lender, user, "n")).hasMessageContaining("No overdue obligation");
        assertThatThrownBy(() -> service.initiateClose(trade.getId(), lender, user)).hasMessageContaining("not open");
        assertThatThrownBy(() -> service.resolveDispute(trade.getId(), user, DisputeResolution.CANCEL, "basis", null, UUID.randomUUID()))
                .hasMessageContaining("before opening");
        service.resolveDispute(trade.getId(), user, DisputeResolution.RESUME, "Court order 4711", "evidence reviewed", UUID.randomUUID());
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.PENDING_CLOSE);
    }

    @Test void onlyThePayerCanDeclareALeg(){
        trade.setStatus(TradeStatus.PENDING_CLOSE);
        assertThatThrownBy(() -> service.declareLegSent(trade.getId(), lender, user, RepoTradeService.Phase.CLOSE, RepoTradeService.SettlementLeg.CASH, "r"))
                .isInstanceOf(AccessDeniedException.class);
        service.declareLegSent(trade.getId(), borrower, user, RepoTradeService.Phase.CLOSE, RepoTradeService.SettlementLeg.CASH, "r");
        assertThat(trade.getCloseCashDeclaredAt()).isNotNull();
    }

    private LegalEntity entity(UUID id){LegalEntity e=new LegalEntity();e.setId(id);e.setCurrentName("Firm");return e;}
    private Asset asset(){Asset a=new Asset();a.setId(trade.getCollateralAssetId());a.setName("Bond");return a;}

    @Test void partyFlaggedNoteIsGenericAndDetailsGoToAuditOnly(){
        when(controls.eligibilityReasons(lender)).thenReturn(List.of("has an unresolved sanctions-screening result"));
        when(controls.eligibilityReasons(borrower)).thenReturn(List.of());
        service.confirmOpenLeg(trade.getId(),borrower,user,RepoTradeService.SettlementLeg.CASH,"cash-1");
        ArgumentCaptor<RepoLifecycleEvent> saved=ArgumentCaptor.forClass(RepoLifecycleEvent.class);
        verify(events,atLeastOnce()).save(saved.capture());
        RepoLifecycleEvent flagged=saved.getAllValues().stream().filter(e->e.getEventType()==LifecycleEventType.PARTY_FLAGGED).findFirst().orElseThrow();
        assertThat(flagged.getNote()).isEqualTo("A party to this trade is no longer eligible; the registry operator has been informed.")
                .doesNotContain(lender.toString()).doesNotContain("sanctions");
        ArgumentCaptor<Object> published=ArgumentCaptor.forClass(Object.class);
        verify(publisher,atLeastOnce()).publishEvent(published.capture());
        assertThat(published.getAllValues().toString()).contains("sanctions-screening");
    }

    @Test void lifecycleNotesAreClippedToTheColumnLength(){
        assertThat(RepoTradeService.clip("x".repeat(1500))).hasSize(1000);
        assertThat(RepoTradeService.clip("short")).isEqualTo("short");
        assertThat(RepoTradeService.clip(null)).isNull();
    }
}
