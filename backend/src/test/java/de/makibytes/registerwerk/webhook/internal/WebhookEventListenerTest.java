package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.AssetApprovedEvent;
import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDisputedEvent;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookEventListener unit tests (Track 6-1)")
class WebhookEventListenerTest {

    @Mock private WebhookDispatchService dispatchService;
    @Mock private AssetRepository assetRepository;
    @Mock private TradeExecutionRepository tradeExecutionRepository;

    private WebhookEventListener listener;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        listener = new WebhookEventListener(dispatchService, assetRepository, tradeExecutionRepository);
    }

    @Test
    @DisplayName("KycApprovedEvent dispatches directly to the entity")
    void on_kycApproved_dispatchesToEntity() {
        UUID entityId = UUID.randomUUID();

        listener.on(new KycApprovedEvent(entityId, UUID.randomUUID(), null, Map.of()));

        verify(dispatchService).dispatch(eq(entityId), eq(WebhookEventType.KYC_APPROVED), any());
    }

    @Test
    @DisplayName("AssetApprovedEvent resolves the issuer entity via the asset before dispatching")
    void on_assetApproved_resolvesIssuerEntity() {
        UUID assetId = UUID.randomUUID();
        UUID issuerId = UUID.randomUUID();
        Asset asset = new Asset();
        asset.setIssuerId(issuerId);
        asset.setName("Test Bond");
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));

        listener.on(new AssetApprovedEvent(assetId, UUID.randomUUID(), null));

        verify(dispatchService).dispatch(eq(issuerId), eq(WebhookEventType.ASSET_APPROVED), any());
    }

    @Test
    @DisplayName("AssetApprovedEvent for an unknown asset does not dispatch")
    void on_assetApproved_unknownAsset_doesNotDispatch() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        listener.on(new AssetApprovedEvent(assetId, UUID.randomUUID(), null));

        verify(dispatchService, never()).dispatch(any(), any(), any());
    }

    @Test
    @DisplayName("TradeExecutedEvent dispatches to both the buyer and the seller")
    void on_tradeExecuted_dispatchesToBothSides() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();

        listener.on(new TradeExecutedEvent(UUID.randomUUID(), UUID.randomUUID(), "TRADER", UUID.randomUUID(),
                UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, buyerId, sellerId));

        verify(dispatchService).dispatch(eq(buyerId), eq(WebhookEventType.TRADE_EXECUTED), any());
        verify(dispatchService).dispatch(eq(sellerId), eq(WebhookEventType.TRADE_EXECUTED), any());
    }

    @Test
    @DisplayName("TradePaymentDisputedEvent resolves the buyer via the trade execution and dispatches only to them")
    void on_tradePaymentDisputed_dispatchesOnlyToBuyer() {
        UUID executionId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        TradeExecution execution = new TradeExecution();
        execution.setBuyerEntityId(buyerId);
        execution.setSellerEntityId(UUID.randomUUID());
        when(tradeExecutionRepository.findById(executionId)).thenReturn(Optional.of(execution));

        listener.on(new TradePaymentDisputedEvent(executionId, UUID.randomUUID(), "TRADER", "never received"));

        verify(dispatchService).dispatch(eq(buyerId), eq(WebhookEventType.TRADE_PAYMENT_DISPUTED), any());
        verify(dispatchService, never()).dispatch(eq(execution.getSellerEntityId()), any(), any());
    }
}
