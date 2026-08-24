package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoTradeServiceTest {
    @Mock RepoTradeRepository trades; @Mock RepoLifecycleEventRepository events;
    @Mock LegalEntityRepository entities; @Mock AssetRepository assets;
    RepoTradeService service; RepoTrade trade; UUID borrower=UUID.randomUUID(),lender=UUID.randomUUID();

    @BeforeEach void setUp(){RepoDeskProperties p=new RepoDeskProperties();p.setEnabled(true);p.setReleaseApproved(true);
        service=new RepoTradeService(p,trades,events,entities,assets);trade=new RepoTrade();trade.setId(UUID.randomUUID());
        trade.setCashBorrowerEntityId(borrower);trade.setCashLenderEntityId(lender);trade.setCollateralAssetId(UUID.randomUUID());
        trade.setCollateralQuantity(new BigDecimal("100"));trade.setCashAmount(new BigDecimal("90000"));
        trade.setRepurchaseAmount(new BigDecimal("90500"));
        // RepoTradeService.confirmOpenLeg checks LocalDate.now(ZoneOffset.UTC) against
        // startDate — using the system-default-zone LocalDate.now() here instead makes this
        // test fail deterministically for any zone ahead of UTC (e.g. CEST) during the ~2h
        // window where the local date has already rolled over but the UTC date hasn't yet.
        trade.setStartDate(LocalDate.now(ZoneOffset.UTC));trade.setEndDate(LocalDate.now(ZoneOffset.UTC).plusDays(30));
        when(trades.findByIdForUpdate(trade.getId())).thenReturn(Optional.of(trade));when(entities.existsById(any())).thenReturn(true);
        lenient().when(entities.findById(any())).thenAnswer(i->Optional.of(entity(i.getArgument(0))));
        lenient().when(assets.findById(trade.getCollateralAssetId())).thenReturn(Optional.of(asset()));
        lenient().when(events.findByRepoTradeIdOrderByCreatedAtAsc(trade.getId())).thenReturn(List.of());}

    @Test void opensOnlyAfterBothRecipientsConfirmAndDuplicateConfirmationIsIdempotent(){
        service.confirmOpenLeg(trade.getId(),borrower,UUID.randomUUID(),RepoTradeService.SettlementLeg.CASH,"cash-1");
        assertThat(trade.isOpenCashConfirmed()).isTrue();assertThat(trade.getStatus()).isEqualTo(TradeStatus.PENDING_OPEN_SETTLEMENT);
        service.confirmOpenLeg(trade.getId(),lender,UUID.randomUUID(),RepoTradeService.SettlementLeg.COLLATERAL,"sec-1");
        assertThat(trade.isOpenCollateralConfirmed()).isTrue();assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
        service.confirmOpenLeg(trade.getId(),borrower,UUID.randomUUID(),RepoTradeService.SettlementLeg.CASH,"cash-1");
        verify(events,times(3)).save(any());
    }

    @Test void rejectsInvalidAmountsEvenWhenCalledOutsideTheWebLayer(){
        trade.setStatus(TradeStatus.OPEN);

        assertThatThrownBy(() -> service.issueMarginCall(trade.getId(), lender, UUID.randomUUID(),
                BigDecimal.ZERO, Instant.now().plusSeconds(60), "call"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> service.requestSubstitution(trade.getId(), borrower, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("-1"), "replacement"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        verifyNoInteractions(events);
    }
    private LegalEntity entity(UUID id){LegalEntity e=new LegalEntity();e.setId(id);e.setCurrentName("Firm");return e;}
    private Asset asset(){Asset a=new Asset();a.setId(trade.getCollateralAssetId());a.setName("Bond");return a;}
}
