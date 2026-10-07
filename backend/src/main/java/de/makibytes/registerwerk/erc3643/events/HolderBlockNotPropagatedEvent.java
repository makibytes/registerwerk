package de.makibytes.registerwerk.erc3643.events;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import java.util.Map;
import java.util.UUID;

/**
 * A §16 eWpG Sperrvermerk is in the register but NOT (yet) enforced on-chain, so on-chain paths that rely on
 * the frozen flag (repo repay/liquidate, direct transfers) stay open for that wallet. The register-level block
 * stays authoritative; this event is the audit record, and an operator task plus an alert accompany it.
 * Published by the Sperrvermerk on-chain sync ({@code SperrvermerkFreezeService}); {@code details.cause} says why:
 * <ul>
 *   <li>{@code NO_DEPLOYMENT_MATCHED} (T3-15) - an asset-scoped block matches no register row although the asset has
 *       EVM deployments;</li>
 *   <li>{@code SUBMISSION_FAILED} - the freeze could not be submitted (signer, RPC, bad state);</li>
 *   <li>{@code TX_FAILED} - the freeze was submitted but reverted, was replaced or never mined (H5: the outcome
 *       is now read from the transaction status);</li>
 *   <li>{@code UNSUPPORTED_ON_CHAIN} - the deployment's standard/chain has no automated, outcome-tracked
 *       freeze (SPL, Stellar, Starknet, Canton, confidential ERC-20);</li>
 *   <li>{@code DRIFT} - the nightly read-back found the wallet not frozen although the freeze was confirmed.</li>
 * </ul>
 */
public record HolderBlockNotPropagatedEvent(UUID holderBlockId, Map<String, Object> details)
        implements AuditableEvent {
    public String eventType()   { return "HOLDER_BLOCK_NOT_PROPAGATED"; }
    public String subjectType() { return "HolderBlock"; }
    public UUID   subjectId()   { return holderBlockId; }
    public UUID   actorId()     { return null; }
    public String actorRole()   { return "SYSTEM"; }
    public Map<String, Object> payload() { return details != null ? details : Map.of(); }
}
