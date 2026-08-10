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
    RepoDeskProperties properties;
    RepoDeskService service;

    @BeforeEach void setUp() {
        properties = new RepoDeskProperties(); properties.setEnabled(true); properties.setReleaseApproved(true);
        service = new RepoDeskService(properties, rfqs, quotes, trades, events, entities, assets);
    }

    @Test void acceptedBorrowCashQuoteCreatesTradeWithAct360RepurchaseAmount() {
        UUID requesterId=UUID.randomUUID(), dealerId=UUID.randomUUID(), rfqId=UUID.randomUUID(), quoteId=UUID.randomUUID();
        RepoRfq rfq=rfq(rfqId,requesterId); rfq.setSide(Side.BORROW_CASH);
        RepoQuote quote=new RepoQuote(); quote.setId(quoteId); quote.setRfqId(rfqId); quote.setQuotingEntityId(dealerId);
        quote.setCashAmount(new BigDecimal("100000")); quote.setRepoRate(new BigDecimal("5.00"));
        quote.setHaircutBps(200); quote.setValidUntil(Instant.now().plusSeconds(3600));
        when(rfqs.findByIdForUpdate(rfqId)).thenReturn(Optional.of(rfq));
        when(quotes.findById(quoteId)).thenReturn(Optional.of(quote));
        when(quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfqId)).thenReturn(List.of(quote));
        when(trades.findByRfqId(rfqId)).thenReturn(Optional.empty());
        when(trades.save(any())).thenAnswer(invocation->{RepoTrade t=invocation.getArgument(0);t.setId(UUID.randomUUID());return t;});
        when(assets.findById(rfq.getCollateralAssetId())).thenReturn(Optional.of(asset(rfq.getCollateralAssetId())));
        when(entities.findById(requesterId)).thenReturn(Optional.of(entity(requesterId,"Requester")));

        var result=service.acceptQuote(rfqId,quoteId,requesterId);

        ArgumentCaptor<RepoTrade> captor=ArgumentCaptor.forClass(RepoTrade.class); verify(trades).save(captor.capture());
        RepoTrade trade=captor.getValue();
        assertThat(trade.getCashBorrowerEntityId()).isEqualTo(requesterId);
        assertThat(trade.getCashLenderEntityId()).isEqualTo(dealerId);
        assertThat(trade.getRepurchaseAmount()).isEqualByComparingTo("100416.666666666666666667");
        assertThat(result.rfq().getStatus()).isEqualTo(RfqStatus.MATCHED);
        assertThat(result.targetEntityIds()).isEmpty();
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
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

    private RepoRfq rfq(UUID id,UUID requester){RepoRfq r=new RepoRfq();r.setId(id);r.setRequesterEntityId(requester);
        r.setVisibility(Visibility.BROADCAST);r.setCollateralAssetId(UUID.randomUUID());r.setCollateralQuantity(new BigDecimal("100"));
        r.setCashAmount(new BigDecimal("100000"));r.setCashCurrency("EUR");r.setStartDate(LocalDate.of(2026,9,1));
        r.setEndDate(LocalDate.of(2026,10,1));r.setExpiresAt(Instant.now().plusSeconds(7200));return r;}
    private LegalEntity entity(UUID id,String name){LegalEntity e=new LegalEntity();e.setId(id);e.setCurrentName(name);e.setStatus(EntityStatus.ACTIVE);return e;}
    private Asset asset(UUID id){Asset a=new Asset();a.setId(id);a.setName("Green Bond");a.setStatus(AssetStatus.ISSUED);return a;}
}
