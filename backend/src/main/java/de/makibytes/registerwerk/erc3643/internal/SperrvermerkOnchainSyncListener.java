package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.events.BlockchainTxStatusEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockCreatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockLiftedEvent;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Event entry points of the §16 eWpG Sperrvermerk -> on-chain freeze sync. The work (which admin path freezes which
 * standard, the per-deployment outcome table, alerts, retries) lives in {@link SperrvermerkFreezeService}; this class
 * only translates events. Previously {@code SperrvermerkService.create}/{@code lift} only ever wrote
 * {@code holder_block} rows and published audit events: nothing called the token's own {@code freezeAddress} /
 * {@code setAddressFrozen}, and {@code EwpgRepoMarket}'s {@code repay}/{@code liquidate} are deliberately ungated by
 * ecosystem permissions, relying entirely on that on-chain frozen flag as the real compliance chokepoint (the backend
 * never mediates those calls directly). Without the sync a legally blocked holder could still repay/liquidate/withdraw
 * pledged securities on-chain although the register shows them blocked.
 *
 * <p>Lives in {@code erc3643.internal} rather than {@code blockchain.internal} or {@code kyc.internal} because it needs
 * both {@link Erc3643LifecycleService} (same module) and the {@code blockchain.api} admin ports; {@code erc3643} already
 * depends one-way on {@code blockchain.api} and {@code kyc.api}, placing it in either of those would create a cycle.
 *
 * <p>On-chain trouble never rolls back or unblocks the Sperrvermerk (the register record is the legally authoritative
 * one). Since H5 it is no longer swallowed either: every submission failure, failed transaction and standard/chain
 * without a freeze is a recorded outcome with an audit event, an operator task and an alert, and the freeze is retried
 * and reconciled nightly.
 *
 * <p>T3-15: wallets are matched in canonical form ({@link AddressNormalizer}) - a checksum-cased block used to match no
 * {@code asset_holder} row, so nothing was frozen and nothing was logged. T3-16: the lift path uses the dedicated
 * block-lift unfreeze variants; the manual unfreeze endpoints refuse while any ACTIVE block covers the wallet.
 */
@Component
class SperrvermerkOnchainSyncListener {

    private final SperrvermerkFreezeService freezeService;

    SperrvermerkOnchainSyncListener(SperrvermerkFreezeService freezeService) {
        this.freezeService = freezeService;
    }

    @ApplicationModuleListener
    void onHolderBlockCreated(HolderBlockCreatedEvent event) {
        propagateFreeze(event.holderBlockId(), event.payload());
    }

    @ApplicationModuleListener
    void onHolderBlockLifted(HolderBlockLiftedEvent event) {
        freezeService.release(event.holderBlockId(), walletsOf(event.payload()));
    }

    /**
     * H5: the outcome of a submitted freeze/unfreeze is read from its transaction status; a SUCCESS confirms it and a
     * FAILED/REPLACED one is reported (TIMEOUT is not a verdict, the transaction may still be mined).
     */
    @ApplicationModuleListener
    void onTransactionStatus(BlockchainTxStatusEvent event) {
        Object hash = event.details() == null ? null : event.details().get("txHash");
        if (hash != null) {
            freezeService.onTransactionStatus(String.valueOf(hash));
        }
    }

    private void propagateFreeze(UUID holderBlockId, Map<String, Object> payload) {
        freezeService.propagate(holderBlockId, uuidDetail(payload, "assetId"), walletsOf(payload),
                SperrvermerkFreezeService.REASON_PREFIX + stringDetail(payload, "legalBasis"));
    }

    /** The block's wallet plus, for an entity-scoped block, the entity's other holder wallets (6-25). */
    private static Set<String> walletsOf(Map<String, Object> payload) {
        Set<String> wallets = new LinkedHashSet<>();
        String primary = AddressNormalizer.normalize(stringDetail(payload, "walletAddress"));
        if (primary != null) {
            wallets.add(primary);
        }
        if (payload.get("walletAddresses") instanceof Collection<?> more) {
            for (Object o : more) {
                String w = o == null ? null : AddressNormalizer.normalize(o.toString());
                if (w != null && !w.isBlank()) {
                    wallets.add(w);
                }
            }
        }
        return wallets;
    }

    private static String stringDetail(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }

    private static UUID uuidDetail(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
