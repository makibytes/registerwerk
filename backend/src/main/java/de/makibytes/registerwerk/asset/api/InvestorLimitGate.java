package de.makibytes.registerwerk.asset.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public API facade for per-investor limit checks (F-BLOCKER-12), consulted from outside the
 * {@code asset} module — currently by {@code trading} at secondary-market settlement/listing
 * time, mirroring how {@code screening.ScreeningGate} exposes a fail-closed check without
 * crossing into {@code asset/internal/}.
 */
public interface InvestorLimitGate {

    /** The investor's maximum total holding in this asset — an override if one exists, else the
     *  asset's own default, else {@code null} (unrestricted). */
    BigDecimal effectiveMaxHolding(Asset asset, UUID investorEntityId);

    /** True if this investor's position in this asset is currently under a lockup. */
    boolean isLockedUp(UUID assetId, UUID investorEntityId);
}
