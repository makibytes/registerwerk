package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record CantonCouponPaidEvent(UUID deploymentId, UUID actorId, LocalDate paymentDate, BigDecimal amountPerUnit, String txRef) implements AuditableEvent {
    public String eventType()   { return "CANTON_COUPON_PAID"; }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public String actorRole()   { return "REGISTRY_ADMIN"; }
    public Map<String, Object> payload() {
        return Map.of("paymentDate", paymentDate.toString(), "amountPerUnit", amountPerUnit.toPlainString());
    }
}
