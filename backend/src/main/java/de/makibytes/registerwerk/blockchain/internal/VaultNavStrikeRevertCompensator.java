package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code VAULT_NAV_STRIKE_CONFIRMED} — undoes {@code
 * asset_vault_state.latest_nav_*} having been advanced to a {@link VaultNavStrike} whose
 * confirming {@code setNavPerShare} block was later retracted. Restores the previous confirmed
 * strike's values (or nulls them out if this was the asset's first-ever confirmed strike), rather
 * than restoring from a {@code beforeState} snapshot — {@link VaultNavStrike} history rows are
 * never deleted, so the prior value is always re-derivable from still-current rows.
 *
 * <p>The {@link VaultNavStrike} row itself is left untouched (its {@code confirmed} flag stays
 * {@code true} — it genuinely was mined and confirmed at some point; only the fact that it was the
 * asset's <i>current</i> NAV is what a reorg past FINALIZED can invalidate).
 *
 * <p>Talks to {@link AssetVaultStateRepository}/{@link VaultNavStrikeRepository} directly, never
 * {@code VaultConfirmationListener} or {@code Erc4626AdminService} (both would introduce the
 * circular Spring-bean dependency described in
 * {@code blockchain.internal.tx.BlockchainTxRevertCompensator}'s javadoc).
 */
@Component
class VaultNavStrikeRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "VAULT_NAV_STRIKE_CONFIRMED";

    private static final Logger log = LoggerFactory.getLogger(VaultNavStrikeRevertCompensator.class);

    private final VaultNavStrikeRepository navStrikeRepository;
    private final AssetVaultStateRepository vaultStateRepository;

    VaultNavStrikeRevertCompensator(
            VaultNavStrikeRepository navStrikeRepository, AssetVaultStateRepository vaultStateRepository) {
        this.navStrikeRepository = navStrikeRepository;
        this.vaultStateRepository = vaultStateRepository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        VaultNavStrike strike = navStrikeRepository.findById(id).orElse(null);
        if (strike == null) {
            return new CompensationOutcome.NotApplicable("VaultNavStrike " + id + " no longer exists");
        }
        AssetVaultState state = vaultStateRepository.findById(strike.getAssetId()).orElse(null);
        if (state == null || state.getLatestNavStrikeAt() == null
                || !state.getLatestNavStrikeAt().equals(strike.getEffectiveAt())) {
            return new CompensationOutcome.NotApplicable("AssetVaultState for asset=" + strike.getAssetId()
                    + " no longer reflects VaultNavStrike " + id + " (already superseded or missing)");
        }

        VaultNavStrike previous = navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(strike.getAssetId())
                .stream()
                .filter(VaultNavStrike::isConfirmed)
                .filter(s -> !s.getId().equals(strike.getId()))
                .filter(s -> s.getStrikeId() < strike.getStrikeId())
                .max(Comparator.comparingLong(VaultNavStrike::getStrikeId))
                .orElse(null);

        log.error("VaultNavStrike id={} asset={} strikeId={} was applied to AssetVaultState but its confirming "
                        + "block was retracted by a reorg deep enough to cross FINALIZED — reverting to the "
                        + "previous confirmed strike ({}).",
                id, strike.getAssetId(), strike.getStrikeId(),
                previous != null ? previous.getStrikeId() : "none — nulling out");

        if (previous != null) {
            state.setLatestNavPerShare(previous.getNavPerShare());
            state.setLatestNavStrikeAt(previous.getEffectiveAt());
            state.setLatestNavReportHash(previous.getReportHash());
        } else {
            state.setLatestNavPerShare(null);
            state.setLatestNavStrikeAt(null);
            state.setLatestNavReportHash(null);
        }
        vaultStateRepository.save(state);

        return new CompensationOutcome.Compensated("Reverted AssetVaultState for asset=" + strike.getAssetId()
                + " past retracted VaultNavStrike " + id);
    }
}
