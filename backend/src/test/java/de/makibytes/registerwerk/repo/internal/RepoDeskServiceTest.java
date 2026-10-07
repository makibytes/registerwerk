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
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoDeskServiceTest {
    @Mock RepoRfqRepository rfqs;
    @Mock RepoQuoteRepository quotes;
    @Mock RepoTradeRepository trades;
    @Mock RepoLifecycleEventRepository events;
    @Mock LegalEntityRepository entities;
    @Mock AssetRepository assets;
    @Mock RepoControls controls;
    @Mock RepoDeskParticipantRepository participants;
    @Mock RepoCorporateActionListener corporateActions;
    @Mock de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository bondTerms;
    @Mock de.makibytes.registerwerk.deployment.api.AssetHolderRepository holders;
    @Mock org.springframework.context.ApplicationEventPublisher publisher;
    RepoDeskProperties properties;
    RepoDeskService service;

    @BeforeEach void setUp() {
        properties = new RepoDeskProperties(); properties.setEnabled(true); properties.setReleaseApproved(true);
        service = new RepoDeskService(properties, rfqs, quotes, trades, events, entities, assets, controls, participants, corporateActions, bondTerms, holders, publisher);
    }

    @Test void acceptedBorrowCashQuoteCreatesTradeWithAct360RepurchaseAmount() {
        UUID requesterId=UUID.randomUUID(), dealerId=UUID.randomUUID(), rfqId=UUID.randomUUID(), quoteId=UUID.randomUUID();
        RepoRfq rfq=rfq(rfqId,requesterId); rfq.setSide(Side.BORROW_CASH);
        RepoQuote quote=new RepoQuote(); quote.setId(quoteId); quote.setRfqId(rfqId); quote.setQuotingEntityId(dealerId);
        quote.setCashAmount(new BigDecimal("100000")); quote.setRepoRate(new BigDecimal("5.00"));
        quote.setHaircutBps(200); quote.setValidUntil(Instant.now().plusSeconds(3600));
        quote.setStatus(QuoteStatus.ACTIVE);
        when(rfqs.findByIdForUpdate(rfqId)).thenReturn(Optional.of(rfq));
        when(quotes.findById(quoteId)).thenReturn(Optional.of(quote));
        when(quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfqId)).thenReturn(List.of(quote));
        when(trades.findByRfqId(rfqId)).thenReturn(Optional.empty());
        when(trades.save(any())).thenAnswer(invocation->{RepoTrade t=invocation.getArgument(0);t.setId(UUID.randomUUID());return t;});
        when(assets.findById(rfq.getCollateralAssetId())).thenReturn(Optional.of(asset(rfq.getCollateralAssetId())));
        when(entities.findById(requesterId)).thenReturn(Optional.of(entity(requesterId,"Requester")));
        when(entities.findById(dealerId)).thenReturn(Optional.of(entity(dealerId,"Dealer")));

        var result=service.acceptQuote(rfqId,quoteId,requesterId,RepoTerms.hash(rfq,quote));

        ArgumentCaptor<RepoTrade> captor=ArgumentCaptor.forClass(RepoTrade.class); verify(trades).save(captor.capture());
        RepoTrade trade=captor.getValue();
        assertThat(trade.getCashBorrowerEntityId()).isEqualTo(requesterId);
        assertThat(trade.getCashLenderEntityId()).isEqualTo(dealerId);
        assertThat(trade.getRepurchaseAmount()).isEqualByComparingTo("100416.67");
        assertThat(result.rfq().getStatus()).isEqualTo(RfqStatus.MATCHED);
        assertThat(result.targetEntityIds()).isEmpty();
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(trade.getUti()).startsWith("REQLEI").hasSize(52);
        assertThat(trade.getTermsHash()).isEqualTo(RepoTerms.hash(rfq,quote));
        assertThat(trade.getAcceptedQuoteVersion()).isEqualTo(1);
    }

    @Test void openRefusedOnFrozenRegister() {
        // 9A-07: an RFQ raised before the handover must not turn into a pledge once the register is frozen.
        UUID requesterId=UUID.randomUUID(), dealerId=UUID.randomUUID(), rfqId=UUID.randomUUID(), quoteId=UUID.randomUUID();
        RepoRfq rfq=rfq(rfqId,requesterId); rfq.setSide(Side.BORROW_CASH);
        RepoQuote quote=new RepoQuote(); quote.setId(quoteId); quote.setRfqId(rfqId); quote.setQuotingEntityId(dealerId);
        quote.setCashAmount(new BigDecimal("100000")); quote.setRepoRate(new BigDecimal("5.00"));
        quote.setHaircutBps(200); quote.setValidUntil(Instant.now().plusSeconds(3600));
        quote.setStatus(QuoteStatus.ACTIVE);
        when(rfqs.findByIdForUpdate(rfqId)).thenReturn(Optional.of(rfq));
        when(quotes.findById(quoteId)).thenReturn(Optional.of(quote));
        Asset frozen=asset(rfq.getCollateralAssetId()); frozen.setStatus(AssetStatus.TRANSFER_PENDING);
        when(assets.findById(rfq.getCollateralAssetId())).thenReturn(Optional.of(frozen));
        when(entities.findById(requesterId)).thenReturn(Optional.of(entity(requesterId,"Requester")));
        when(entities.findById(dealerId)).thenReturn(Optional.of(entity(dealerId,"Dealer")));

        assertThatThrownBy(() -> service.acceptQuote(rfqId,quoteId,requesterId,RepoTerms.hash(rfq,quote)))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class)
                .hasMessageContaining("Repo trade");
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.OPEN);
        verify(trades, never()).save(any());
    }

    @Test void targetedRfqRejectsUninvitedDealer() {
        UUID requesterId=UUID.randomUUID(), invitedId=UUID.randomUUID(), outsiderId=UUID.randomUUID();
        RepoRfq rfq=rfq(UUID.randomUUID(),requesterId); rfq.setVisibility(Visibility.TARGETED);
        rfq.setTargetEntityIds(new LinkedHashSet<>(Set.of(invitedId)));
        when(entities.findById(outsiderId)).thenReturn(Optional.of(entity(outsiderId,"Outsider")));
        when(rfqs.findByIdForUpdate(rfq.getId())).thenReturn(Optional.of(rfq));

        assertThatThrownBy(() -> service.submitQuote(rfq.getId(),outsiderId,UUID.randomUUID(),
                new RepoDeskService.SubmitQuote(new BigDecimal("100000"),new BigDecimal("3.5"),200,
                        Instant.now().plusSeconds(600),null)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("cannot quote");
        verifyNoInteractions(quotes);
    }

    @Test void acceptWithStaleTermsHashIsRefusedAndNothingIsMatched() {
        UUID requesterId=UUID.randomUUID(), dealerId=UUID.randomUUID(), rfqId=UUID.randomUUID(), quoteId=UUID.randomUUID();
        RepoRfq rfq=rfq(rfqId,requesterId); rfq.setSide(Side.BORROW_CASH);
        RepoQuote quote=new RepoQuote(); quote.setId(quoteId); quote.setRfqId(rfqId); quote.setQuotingEntityId(dealerId);
        quote.setCashAmount(new BigDecimal("100000")); quote.setRepoRate(new BigDecimal("9.00"));
        quote.setHaircutBps(200); quote.setValidUntil(Instant.now().plusSeconds(3600)); quote.setStatus(QuoteStatus.ACTIVE);
        String seen = RepoTerms.hash(rfq, quote);
        quote.setRepoRate(new BigDecimal("5.00")); // the row was changed after the requester viewed it
        when(rfqs.findByIdForUpdate(rfqId)).thenReturn(Optional.of(rfq));
        when(quotes.findById(quoteId)).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> service.acceptQuote(rfqId, quoteId, requesterId, seen))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("terms changed");
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.OPEN);
        verify(trades, never()).save(any());
    }

    @Test void acceptOfSupersededQuoteIsRefused() {
        UUID requesterId=UUID.randomUUID(), dealerId=UUID.randomUUID(), rfqId=UUID.randomUUID(), quoteId=UUID.randomUUID();
        RepoRfq rfq=rfq(rfqId,requesterId);
        RepoQuote quote=new RepoQuote(); quote.setId(quoteId); quote.setRfqId(rfqId); quote.setQuotingEntityId(dealerId);
        quote.setCashAmount(new BigDecimal("100000")); quote.setRepoRate(new BigDecimal("5")); quote.setHaircutBps(200);
        quote.setValidUntil(Instant.now().plusSeconds(3600)); quote.setStatus(QuoteStatus.SUPERSEDED);
        when(rfqs.findByIdForUpdate(rfqId)).thenReturn(Optional.of(rfq));
        when(quotes.findById(quoteId)).thenReturn(Optional.of(quote));
        when(quotes.findByRfqIdAndQuotingEntityIdAndStatus(rfqId, dealerId, QuoteStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptQuote(rfqId, quoteId, requesterId, "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("superseded");
    }

    @Test void quoteSubmissionInsertsNewVersionAndSupersedesPrevious() {
        UUID requesterId=UUID.randomUUID(), dealerId=UUID.randomUUID();
        RepoRfq rfq=rfq(UUID.randomUUID(),requesterId); rfq.setExpiresAt(Instant.now().plusSeconds(7200));
        RepoQuote previous=new RepoQuote(); previous.setId(UUID.randomUUID()); previous.setStatus(QuoteStatus.ACTIVE); previous.setQuoteVersion(1);
        when(entities.findById(dealerId)).thenReturn(Optional.of(entity(dealerId,"Dealer")));
        when(rfqs.findByIdForUpdate(rfq.getId())).thenReturn(Optional.of(rfq));
        when(quotes.findByRfqIdAndQuotingEntityIdAndStatus(rfq.getId(),dealerId,QuoteStatus.ACTIVE)).thenReturn(Optional.of(previous));
        when(quotes.maxVersion(rfq.getId(),dealerId)).thenReturn(1);
        when(quotes.save(any())).thenAnswer(i->{RepoQuote q=i.getArgument(0);q.setId(UUID.randomUUID());return q;});
        when(assets.findById(rfq.getCollateralAssetId())).thenReturn(Optional.of(asset(rfq.getCollateralAssetId())));
        when(entities.findById(requesterId)).thenReturn(Optional.of(entity(requesterId,"Requester")));

        service.submitQuote(rfq.getId(),dealerId,UUID.randomUUID(),new RepoDeskService.SubmitQuote(new BigDecimal("100000"),
                new BigDecimal("4"),200,Instant.now().plusSeconds(600),null));

        assertThat(previous.getStatus()).isEqualTo(QuoteStatus.SUPERSEDED);
        ArgumentCaptor<RepoQuote> captor=ArgumentCaptor.forClass(RepoQuote.class); verify(quotes).save(captor.capture());
        assertThat(captor.getValue().getQuoteVersion()).isEqualTo(2);
        assertThat(captor.getValue().getStatus()).isEqualTo(QuoteStatus.ACTIVE);
    }

    @Test void currencyRulesRoundInterestToMinorUnitsAndPickDayCount() {
        assertThat(CurrencyRules.repurchaseAmount("EUR", new BigDecimal("100000"), new BigDecimal("5"),
                LocalDate.of(2026,9,1), LocalDate.of(2026,10,1), 360)).isEqualByComparingTo("100416.67");
        assertThat(CurrencyRules.dayCountBasis("GBP")).isEqualTo(365);
        assertThat(CurrencyRules.dayCountBasis("EUR")).isEqualTo(360);
        assertThat(CurrencyRules.minorUnits("JPY")).isZero();
        assertThatThrownBy(() -> CurrencyRules.requireMinorUnitScale("EUR", new BigDecimal("1.005"), "Cash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void withdrawQuoteLocksTheRfqLikeAccept() {
        UUID requesterId=UUID.randomUUID(), dealerId=UUID.randomUUID(), rfqId=UUID.randomUUID();
        RepoRfq rfq=rfq(rfqId,requesterId);
        RepoQuote quote=new RepoQuote(); quote.setId(UUID.randomUUID()); quote.setRfqId(rfqId); quote.setQuotingEntityId(dealerId);
        quote.setStatus(QuoteStatus.ACTIVE); quote.setValidUntil(Instant.now().plusSeconds(3600));
        quote.setCashAmount(new BigDecimal("100000")); quote.setRepoRate(new BigDecimal("5.00")); quote.setHaircutBps(200);
        when(rfqs.findByIdForUpdate(rfqId)).thenReturn(Optional.of(rfq));
        when(quotes.findByRfqIdAndQuotingEntityIdAndStatus(rfqId,dealerId,QuoteStatus.ACTIVE)).thenReturn(Optional.of(quote));
        lenient().when(quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfqId)).thenReturn(List.of(quote));
        lenient().when(entities.findById(any())).thenReturn(Optional.of(entity(requesterId,"Requester")));
        lenient().when(assets.findById(any())).thenAnswer(i->Optional.of(asset(i.getArgument(0))));

        service.withdrawQuote(rfqId,dealerId);

        verify(rfqs).findByIdForUpdate(rfqId);
        verify(rfqs,never()).findById(rfqId);
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.WITHDRAWN);
    }

    private RepoRfq rfq(UUID id,UUID requester){RepoRfq r=new RepoRfq();r.setId(id);r.setRequesterEntityId(requester);
        r.setVisibility(Visibility.BROADCAST);r.setCollateralAssetId(UUID.randomUUID());r.setCollateralQuantity(new BigDecimal("100"));
        r.setCashAmount(new BigDecimal("100000"));r.setCashCurrency("EUR");r.setStartDate(LocalDate.now(ZoneOffset.UTC).plusDays(1));
        r.setEndDate(LocalDate.now(ZoneOffset.UTC).plusDays(31));r.setExpiresAt(Instant.now().plusSeconds(7200));return r;}
    private LegalEntity entity(UUID id,String name){LegalEntity e=new LegalEntity();e.setId(id);e.setCurrentName(name);e.setStatus(EntityStatus.ACTIVE);e.setLeiCode(name.equals("Requester")?"REQLEI00000000000001":"DEALER00000000000001");return e;}
    private Asset asset(UUID id){Asset a=new Asset();a.setId(id);a.setName("Green Bond");a.setStatus(AssetStatus.ISSUED);return a;}
}
