package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.HolderEncumbranceSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Units a borrower has pledged in open repos (and is about to deliver as approved substitutes).
 * Trading subtracts them from what may be listed, reserved or settled (5A-09 / T5-08 interim:
 * internal encumbrance only, no register-level Sperrvermerk).
 */
@Component
class RepoEncumbranceSource implements HolderEncumbranceSource {
    private final RepoControls controls;

    RepoEncumbranceSource(RepoControls controls) { this.controls = controls; }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal encumbered(UUID entityId, UUID assetId) {
        if (entityId == null || assetId == null) return BigDecimal.ZERO;
        return controls.pledged(entityId, assetId).max(BigDecimal.ZERO);
    }
}
