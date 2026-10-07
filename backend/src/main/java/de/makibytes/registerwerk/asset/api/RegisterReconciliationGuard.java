package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.shared.RegisterNotReconciledException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 9A-05 (interim policy T9-01): single refusal point for register DISCLOSURES (§19 statements, §10 extracts) of an asset
 * whose holder sync is {@link HolderSyncStatus#BLOCKED}. A BLOCKED run writes no holder row at all, so every nominal on
 * the register may be stale, not only the units of the unmapped wallet; a signed or e-mailed disclosure cannot be
 * un-sent. Throws {@link RegisterNotReconciledException} (HTTP 409).
 *
 * <p>Deliberately refused only on BLOCKED: an asset with no on-chain deployment (manually maintained register) or one
 * that was never synced is not "unreconciled"; staleness is shown by {@link #stamp}, not refused.
 */
public final class RegisterReconciliationGuard {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private RegisterReconciliationGuard() {}

    /** True when the asset's chain-derived register is known to be unreconciled. Null-safe. */
    public static boolean isBlocked(Asset asset) {
        return asset != null && asset.getHolderSyncStatus() == HolderSyncStatus.BLOCKED;
    }

    public static void requireReconciled(Asset asset, String operation) {
        if (!isBlocked(asset)) {
            return;
        }
        Instant last = asset.getLastSuccessfulHolderSyncAt();
        String unmapped = asset.getHolderSyncUnmappedWallets();
        throw new RegisterNotReconciledException(asset.getId(), operation + " refused: the register of asset " + asset.getId()
                + " is being reconciled with the chain (holder sync BLOCKED; last successful sync "
                + (last == null ? "never" : TS.format(last))
                + (unmapped == null || unmapped.isBlank() ? "" : "; unmapped wallets: " + unmapped)
                + "). Try again once the registry operator has resolved it.");
    }

    /** The "reconciled as of" line for a disclosure header. Never claims a reconciliation that did not happen. */
    public static String stamp(Asset asset) {
        Instant last = asset == null ? null : asset.getLastSuccessfulHolderSyncAt();
        if (isBlocked(asset)) {
            return "Register NOT reconciled with the chain" + (last == null ? "" : " (last reconciled " + TS.format(last) + ")");
        }
        return last == null
                ? "No on-chain reconciliation recorded (off-chain register or never reconciled)"
                : "Register reconciled with the chain as of " + TS.format(last);
    }
}
