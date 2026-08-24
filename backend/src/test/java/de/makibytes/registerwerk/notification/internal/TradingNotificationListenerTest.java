package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.notification.api.EmailPort;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDisputedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradingNotificationListener unit tests (Track 5-3)")
class TradingNotificationListenerTest {

    @Mock private EmailPort emailPort;
    @Mock private AppUserRepository appUserRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private TradeExecutionRepository tradeExecutionRepository;

    private TradingNotificationListener listener;
    private final UUID assetId = UUID.randomUUID();
    private final UUID buyerId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new TradingNotificationListener(emailPort, appUserRepository, legalEntityRepository, assetRepository, tradeExecutionRepository);
        lenient().when(legalEntityRepository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(new LegalEntity()));
        Asset asset = new Asset();
        asset.setName("Test Bond");
        lenient().when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
    }

    private AppUser companyAdmin(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setRoles(EnumSet.of(AppUserRole.COMPANY_ADMIN));
        return user;
    }

    @Test
    @DisplayName("TradeExecutedEvent emails both the buyer's and the seller's company admins")
    void on_tradeExecuted_emailsBothSides() {
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(buyerId)).thenReturn(List.of(companyAdmin("buyer@x.example")));
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(sellerId)).thenReturn(List.of(companyAdmin("seller@x.example")));

        listener.on(new TradeExecutedEvent(UUID.randomUUID(), UUID.randomUUID(), "TRADER", UUID.randomUUID(), assetId,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, buyerId, sellerId));

        verify(emailPort).sendHtml(eq("buyer@x.example"), anyString(), eq("trade-executed"), org.mockito.ArgumentMatchers.argThat(m -> "BUY".equals(m.get("side"))));
        verify(emailPort).sendHtml(eq("seller@x.example"), anyString(), eq("trade-executed"), org.mockito.ArgumentMatchers.argThat(m -> "SELL".equals(m.get("side"))));
        verify(emailPort, times(2)).sendHtml(anyString(), anyString(), eq("trade-executed"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("TradePaymentDisputedEvent emails only the buyer, looked up via the trade execution")
    void on_tradePaymentDisputed_emailsOnlyBuyer() {
        UUID executionId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(buyerId);
        execution.setAssetId(assetId);
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(buyerId)).thenReturn(List.of(companyAdmin("buyer@x.example")));

        listener.on(new TradePaymentDisputedEvent(executionId, UUID.randomUUID(), "TRADER", "never received"));

        ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailPort).sendHtml(eq("buyer@x.example"), anyString(), eq("trade-payment-disputed"), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("reason", "never received");
    }
}
