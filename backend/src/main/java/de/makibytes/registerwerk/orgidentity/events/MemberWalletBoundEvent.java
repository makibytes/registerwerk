package de.makibytes.registerwerk.orgidentity.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

public record MemberWalletBoundEvent(UUID memberWalletId, UUID actorId, String actorRole, Map<String, Object> details) implements AuditableEvent {
    public String eventType()   { return "MEMBER_WALLET_BOUND"; }
    public String subjectType() { return "OrgMemberWallet"; }
    public UUID   subjectId()   { return memberWalletId; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
