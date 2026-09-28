package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.shared.RegisterFreeze;

import java.util.UUID;

/**
 * T3-07: single refusal point for register-mutating operations on an asset whose register is
 * frozen for a §§21/22 eWpG handover ({@link AssetStatus#TRANSFER_PENDING}) or already handed to
 * the successor ({@link AssetStatus#TRANSFERRED_OUT}). Throws
 * {@link InvalidStateTransitionException} (HTTP 409).
 */
public final class RegisterFreezeGuard {

    private RegisterFreezeGuard() {}

    /** Refuses when the register is frozen or transferred out. Null status/asset is treated as open. */
    public static void requireOpen(Asset asset, String operation) {
        if (asset != null) {
            requireOpen(asset.getStatus(), asset.getId(), operation);
        }
    }

    public static void requireOpen(AssetStatus status, UUID assetId, String operation) {
        if (status != null) {
            RegisterFreeze.requireOpen(status.name(), assetId, operation);
        }
    }

    /** For callers that only hold the status name (e.g. {@code AssetLookupPort.AssetInfo}). */
    public static void requireOpen(String statusName, UUID assetId, String operation) {
        RegisterFreeze.requireOpen(statusName, assetId, operation);
    }

    /** Looks the asset up; an unknown id is left to the caller's own not-found handling. */
    public static void requireOpen(AssetRepository repository, UUID assetId, String operation) {
        if (assetId != null) {
            repository.findById(assetId).ifPresent(a -> requireOpen(a, operation));
        }
    }

    /** For read-only disclosures (statements, extracts): refused only once transferred out. */
    public static void requireAdministeredHere(Asset asset, String operation) {
        if (asset != null && asset.getStatus() != null && !asset.getStatus().isAdministeredHere()) {
            throw new InvalidStateTransitionException(
                    operation + " refused: the register of asset " + asset.getId()
                            + " was transferred to a successor registrar (TRANSFERRED_OUT)");
        }
    }
}
