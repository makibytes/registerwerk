package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.AssetApprovedEvent;
import de.makibytes.registerwerk.asset.events.AssetRejectedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderAllocatedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderConfirmedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderLapsedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderPaymentConfirmedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderRejectedEvent;
import de.makibytes.registerwerk.asset.events.SubscriptionOrderSettledEvent;
import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectedEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectionCategory;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentConfirmedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDisputedEvent;
import de.makibytes.registerwerk.webhook.api.WebhookEventType;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Fans the curated event set out to {@link WebhookDispatchService} — the module's only inbound
 * coupling to the rest of the application, mirroring how the {@code notification} module's
 * per-source listeners (e.g. {@code TradingNotificationListener}) already consume these same
 * events for email. Deliberately narrow: only {@link WebhookEventType}'s allow-listed events are
 * wired here, not every {@code AuditableEvent} in the system.
 *
 * <p>Every listener opts out of the listener transaction ({@code NOT_SUPPORTED} overrides the
 * {@code REQUIRES_NEW} of {@code @ApplicationModuleListener}): the dispatch service opens its own short
 * transactions, so holding a listener connection across the HTTP send (and acquiring a second one
 * inside) would starve the pool under bursts.
 */
@Component
class WebhookEventListener {

    private final WebhookDispatchService dispatchService;
    private final AssetRepository assetRepository;
    private final TradeExecutionRepository tradeExecutionRepository;

    WebhookEventListener(WebhookDispatchService dispatchService, AssetRepository assetRepository,
                          TradeExecutionRepository tradeExecutionRepository) {
        this.dispatchService = dispatchService;
        this.assetRepository = assetRepository;
        this.tradeExecutionRepository = tradeExecutionRepository;
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(KycApprovedEvent event) {
        dispatchService.dispatch(event.entityId(), WebhookEventType.KYC_APPROVED,
                Map.of("entityId", event.entityId().toString()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(KycRejectedEvent event) {
        // Tipping-off (GwG s.47): only the fixed customer-facing category ever leaves the platform;
        // the operator's free-text internal reason stays in the audit trail.
        Object reasonCode = event.payload().get("reasonCode");
        dispatchService.dispatch(event.entityId(), WebhookEventType.KYC_REJECTED, Map.of(
                "entityId", event.entityId().toString(),
                "reasonCode", reasonCode != null ? reasonCode.toString() : KycRejectionCategory.CONTACT_SUPPORT.name()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(AssetApprovedEvent event) {
        Asset asset = assetRepository.findById(event.assetId()).orElse(null);
        if (asset == null || asset.getIssuerId() == null) return;
        dispatchService.dispatch(asset.getIssuerId(), WebhookEventType.ASSET_APPROVED, Map.of(
                "assetId", event.assetId().toString(), "assetName", asset.getName()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(AssetRejectedEvent event) {
        Asset asset = assetRepository.findById(event.assetId()).orElse(null);
        if (asset == null || asset.getIssuerId() == null) return;
        dispatchService.dispatch(asset.getIssuerId(), WebhookEventType.ASSET_REJECTED, Map.of(
                "assetId", event.assetId().toString(), "assetName", asset.getName(),
                "reason", event.reason() != null ? event.reason() : ""));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(SubscriptionOrderAllocatedEvent event) {
        UUID investorEntityId = investorEntityId(event.payload());
        if (investorEntityId == null) return;
        dispatchService.dispatch(investorEntityId, WebhookEventType.SUBSCRIPTION_ORDER_ALLOCATED,
                stringify(event.payload()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(SubscriptionOrderConfirmedEvent event) {
        UUID investorEntityId = investorEntityId(event.payload());
        if (investorEntityId == null) return;
        dispatchService.dispatch(investorEntityId, WebhookEventType.SUBSCRIPTION_ORDER_CONFIRMED,
                stringify(event.payload()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(SubscriptionOrderPaymentConfirmedEvent event) {
        UUID investorEntityId = investorEntityId(event.payload());
        if (investorEntityId == null) return;
        dispatchService.dispatch(investorEntityId, WebhookEventType.SUBSCRIPTION_ORDER_PAYMENT_CONFIRMED,
                stringify(event.payload()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(SubscriptionOrderSettledEvent event) {
        UUID investorEntityId = investorEntityId(event.payload());
        if (investorEntityId == null) return;
        dispatchService.dispatch(investorEntityId, WebhookEventType.SUBSCRIPTION_ORDER_SETTLED,
                stringify(event.payload()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(SubscriptionOrderLapsedEvent event) {
        UUID investorEntityId = investorEntityId(event.payload());
        if (investorEntityId == null) return;
        dispatchService.dispatch(investorEntityId, WebhookEventType.SUBSCRIPTION_ORDER_LAPSED,
                stringify(event.payload()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(SubscriptionOrderRejectedEvent event) {
        UUID investorEntityId = investorEntityId(event.payload());
        if (investorEntityId == null) return;
        dispatchService.dispatch(investorEntityId, WebhookEventType.SUBSCRIPTION_ORDER_REJECTED,
                stringify(event.payload()));
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(TradeExecutedEvent event) {
        Map<String, Object> payload = Map.of(
                "executionId", event.executionId().toString(), "assetId", event.assetId().toString(),
                "quantity", event.quantity().toPlainString(), "unitPrice", event.unitPrice().toPlainString(),
                "totalPrice", event.totalPrice().toPlainString());
        dispatchService.dispatch(event.buyerEntityId(), WebhookEventType.TRADE_EXECUTED, payload);
        dispatchService.dispatch(event.sellerEntityId(), WebhookEventType.TRADE_EXECUTED, payload);
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(TradePaymentConfirmedEvent event) {
        TradeExecution execution = tradeExecutionRepository.findById(event.executionId()).orElse(null);
        if (execution == null) return;
        Map<String, Object> payload = Map.of("executionId", event.executionId().toString());
        dispatchService.dispatch(execution.getBuyerEntityId(), WebhookEventType.TRADE_PAYMENT_CONFIRMED, payload);
        dispatchService.dispatch(execution.getSellerEntityId(), WebhookEventType.TRADE_PAYMENT_CONFIRMED, payload);
    }

    @ApplicationModuleListener
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void on(TradePaymentDisputedEvent event) {
        TradeExecution execution = tradeExecutionRepository.findById(event.executionId()).orElse(null);
        if (execution == null) return;
        dispatchService.dispatch(execution.getBuyerEntityId(), WebhookEventType.TRADE_PAYMENT_DISPUTED, Map.of(
                "executionId", event.executionId().toString(), "reason", event.reason() != null ? event.reason() : ""));
    }

    private static UUID investorEntityId(Map<String, Object> payload) {
        Object value = payload.get("investorEntityId");
        return value instanceof UUID uuid ? uuid : null;
    }

    private static Map<String, Object> stringify(Map<String, Object> payload) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        payload.forEach((k, v) -> out.put(k, v != null ? v.toString() : ""));
        return out;
    }
}
