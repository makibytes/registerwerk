package de.makibytes.registerwerk.blockchain.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * Fired for every registry-operator administrative action submitted through
 * {@code TokenAdminService.submitAdmin} on an ERC-20/721/1155 deployment: pause/unpause,
 * freeze/unfreeze, whitelist/unwhitelist, forcedTransfer(+Single), forcedApprove,
 * forceBurn(+Single), setSupplyCap, mint, burn.
 *
 * <p>{@code methodName} carries the on-chain method name (e.g. {@code "forcedTransfer"})
 * as the event type, and {@code params} carries the exact call arguments already built by
 * the service (including {@code reason}/{@code legalBasis} where applicable), so this one
 * event fully audits the whole EVM admin/correction surface from a single chokepoint.
 */
public record TokenAdminActionEvent(
        UUID deploymentId, String methodName, UUID actorId, String actorRole, Map<String, Object> params,
        UUID approverId, UUID requestId)
        implements AuditableEvent {

    /** Event without dual-control evidence (single-actor actions, system actors). */
    public TokenAdminActionEvent(
            UUID deploymentId, String methodName, UUID actorId, String actorRole, Map<String, Object> params) {
        this(deploymentId, methodName, actorId, actorRole, params, null, null);
    }

    /** P4C-4: the validated second approver, recorded in the audit payload by {@code AuditEvent.from}. */
    @Override public UUID dualControlApproverId() { return approverId; }

    /** Links to the generic {@code DUAL_CONTROL_APPROVED} entry of the same request. */
    @Override public UUID correlationId() { return requestId; }

    public String eventType()   { return "TOKEN_ADMIN_" + toScreamingSnakeCase(methodName); }
    public String subjectType() { return "AssetDeployment"; }
    public UUID   subjectId()   { return deploymentId; }
    public Map<String, Object> payload() { return params != null ? params : Map.of(); }

    private static String toScreamingSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }
}
