package de.makibytes.registerwerk.asset.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Lets a module that holds claims on the units of an asset (repo desk pledges, lending collateral, in-flight trades)
 * veto the §20 eWpRV register handover of that asset (Wave 2b, 9A-07). Same shape as {@link RedemptionBlocker}:
 * implemented outside {@code asset}/{@code registertransfer} to avoid a dependency cycle and collected by
 * {@code RegisterTransferService.export}, which refuses with every reason. Interim policy (T9-03): block, rather than
 * carry the pledge record to the successor registrar.
 */
public interface HandoverBlocker {

    /** A human-readable reason when the handover of {@code assetId} must be refused, else empty. */
    Optional<String> blocksHandover(UUID assetId);
}
