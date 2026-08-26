package de.makibytes.registerwerk.finality.api;

import java.util.List;
import java.util.UUID;

/**
 * Inverted-dependency recovery hook into the Chaincache lifecycle inbox (owned by the {@code
 * blockchain} module) — defined here, the same way {@link BlockFinalityFeed} inverts the
 * indexer/blockchain-to-finality direction, so that {@code finality.internal.FinalityJournalAdminService}'s
 * quarantine-resolution flow can also clear the inbox's own independent quarantine state without
 * {@code finality} taking a compile-time dependency on {@code blockchain} (which already depends
 * on {@code finality.api}; the reverse direction would create a module cycle).
 *
 * <p>A quarantined lifecycle event and a quarantined chain are two different fail-closed states
 * that happen to share a root cause (a reorg the local finality/compensation machinery couldn't
 * reconcile): {@code chain_quarantine} blocks gated operations on affected assets, while {@code
 * chaincache_event_inbox}/{@code chain_contract_subscription} being {@code QUARANTINED} blocks the
 * durable stream itself from ever advancing past the poison event. Resolving only the former
 * leaves the stream permanently wedged; resolving only the latter would let disqualified events
 * process against wallpapered-over gated state. An operator resolving one almost always needs
 * both cleared together, which is why {@code resolveQuarantine} calls this automatically rather
 * than requiring a second, easy-to-forget manual step.
 */
public interface ChaincacheInboxRecoveryPort {

    /**
     * Clears every {@code QUARANTINED} inbox/subscription row for this chain, letting the durable
     * stream manager's next reconnect resume normal processing.
     *
     * @return the number of inbox rows un-quarantined
     */
    int clearQuarantinedInbox(UUID chainConfigId);

    /** Quarantined inbox rows for this chain, for operator visibility before clearing. */
    List<QuarantinedInboxEvent> listQuarantinedInbox(UUID chainConfigId);

    record QuarantinedInboxEvent(String eventId, String eventKind, String finality,
            String lastError, long deliveryCount, java.time.Instant lastReceivedAt) {
    }
}
