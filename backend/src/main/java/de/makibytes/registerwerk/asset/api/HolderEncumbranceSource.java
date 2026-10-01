package de.makibytes.registerwerk.asset.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Units of an asset that an entity has committed elsewhere and can therefore neither list for
 * sale nor settle away (Phase 5, 5A-09 / T5-08 interim). Implemented by modules that pledge
 * holdings (the repo desk); consumed by {@code trading} when it computes what a seller may list,
 * reserve and settle. Internal bookkeeping only - no register-level Sperrvermerk is written.
 *
 * <p>Implementations must be cheap, read-only and fail closed: throw rather than under-report.
 */
public interface HolderEncumbranceSource {

    /** Units of {@code assetId} currently encumbered for {@code entityId}; never negative. */
    BigDecimal encumbered(UUID entityId, UUID assetId);
}
