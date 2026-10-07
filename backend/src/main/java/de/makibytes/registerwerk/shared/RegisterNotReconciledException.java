package de.makibytes.registerwerk.shared;

import java.util.UUID;

/**
 * A register disclosure (§19 eWpG statement / holding confirmation, §10 eWpG extract) was refused because the asset's
 * chain-derived register is NOT reconciled: the last holder sync was refused (an unmapped wallet holds finalized units,
 * {@code HolderSyncStatus.BLOCKED}) and a BLOCKED run writes nothing, so every holder row of the asset may be stale
 * (review 9A-05). A {@link ComplianceGateException} subtype: it maps to 409, is recorded as a rejected action in the
 * audit log, and carries the asset id so batch callers can warn once per asset.
 */
public class RegisterNotReconciledException extends ComplianceGateException {

    private final UUID assetId;

    public RegisterNotReconciledException(UUID assetId, String message) {
        super(message);
        this.assetId = assetId;
    }

    public UUID getAssetId() {
        return assetId;
    }
}
