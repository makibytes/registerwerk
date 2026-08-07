package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.notification.api.EmailPort;
import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.events.TradeExecutedEvent;
import de.makibytes.registerwerk.trading.events.TradePaymentDisputedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Notifies trade counterparties by email — previously {@code TradeExecutedEvent} had no listener
 * anywhere and neither side of a trade was ever told anything happened. Coupon-payment/bond-
 * maturity notifications (per-holder fan-out from {@code CorporateActionSettledEvent}) are
 * deliberately NOT covered here: that event carries no holder list, and building the fan-out
 * blind risks spamming or missing holders — a proper implementation needs its own bulk-send
 * design, not a quick listener bolted onto this one.
 */
@Component
class TradingNotificationListener {

    private final EmailPort emailPort;
    private final AppUserRepository appUserRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final AssetRepository assetRepository;
    private final TradeExecutionRepository tradeExecutionRepository;

    TradingNotificationListener(EmailPort emailPort, AppUserRepository appUserRepository,
                                 LegalEntityRepository legalEntityRepository, AssetRepository assetRepository,
                                 TradeExecutionRepository tradeExecutionRepository) {
        this.emailPort = emailPort;
        this.appUserRepository = appUserRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.assetRepository = assetRepository;
        this.tradeExecutionRepository = tradeExecutionRepository;
    }

    @ApplicationModuleListener
    void on(TradeExecutedEvent event) {
        Asset asset = assetRepository.findById(event.assetId()).orElse(null);
        String assetName = asset != null ? asset.getName() : "the asset";
        notifyCompanyAdmins(event.buyerEntityId(), "Registerwerk: trade executed", "trade-executed", Map.of(
                "assetName", assetName, "side", "BUY",
                "quantity", event.quantity(), "unitPrice", event.unitPrice(), "totalPrice", event.totalPrice()));
        notifyCompanyAdmins(event.sellerEntityId(), "Registerwerk: trade executed", "trade-executed", Map.of(
                "assetName", assetName, "side", "SELL",
                "quantity", event.quantity(), "unitPrice", event.unitPrice(), "totalPrice", event.totalPrice()));
    }

    @ApplicationModuleListener
    void on(TradePaymentDisputedEvent event) {
        TradeExecution execution = tradeExecutionRepository.findById(event.executionId()).orElse(null);
        if (execution == null) return;
        Asset asset = assetRepository.findById(execution.getAssetId()).orElse(null);
        String assetName = asset != null ? asset.getName() : "the asset";
        notifyCompanyAdmins(execution.getBuyerEntityId(), "Registerwerk: trade payment disputed", "trade-payment-disputed", Map.of(
                "assetName", assetName, "reason", event.reason() != null ? event.reason() : ""));
    }

    private void notifyCompanyAdmins(UUID entityId, String subject, String template, Map<String, Object> baseVars) {
        if (entityId == null) return;
        String entityName = legalEntityRepository.findById(entityId).map(e -> e.getCurrentName()).orElse("your company");
        appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId).stream()
                .filter(user -> user.getRoles().contains(AppUserRole.COMPANY_ADMIN))
                .forEach(admin -> {
                    Map<String, Object> vars = new java.util.HashMap<>(baseVars);
                    vars.put("adminName", admin.getFullName() != null ? admin.getFullName() : admin.getEmail());
                    vars.put("entityName", entityName);
                    emailPort.sendHtml(admin.getEmail(), subject, template, vars);
                });
    }
}
