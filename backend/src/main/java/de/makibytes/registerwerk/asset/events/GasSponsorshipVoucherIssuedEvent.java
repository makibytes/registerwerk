package de.makibytes.registerwerk.asset.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

/** A paymaster voucher was signed for a customer's sponsored UserOperation. */
public record GasSponsorshipVoucherIssuedEvent(
        UUID voucherId, UUID policyId, UUID deploymentId, UUID entityId, String sender,
        long chainId, BigInteger maxCostWei, long validUntil, UUID actorId, String actorRole
) implements AuditableEvent {
    public String eventType()   { return "GAS_SPONSORSHIP_VOUCHER_ISSUED"; }
    public String subjectType() { return "GasSponsorshipVoucher"; }
    public UUID   subjectId()   { return voucherId; }
    public Map<String, Object> payload() {
        return Map.of(
                "policyId", policyId.toString(),
                "deploymentId", deploymentId.toString(),
                "entityId", entityId.toString(),
                "sender", sender,
                "chainId", chainId,
                "maxCostWei", maxCostWei.toString(),
                "validUntil", validUntil);
    }
}
