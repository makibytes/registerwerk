package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.deployment.api.VaultNavStrike;
import de.makibytes.registerwerk.deployment.api.VaultNavStrikeRepository;
import de.makibytes.registerwerk.finality.api.BlockIdentity;
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
 * <p>The {@link VaultNavStrike} confirmation is reset as well. Keeping an orphaned strike marked
 * confirmed would prevent the listener from ever re-verifying it and could make it a candidate
 * for a later NAV restoration merely because it once appeared on a discarded branch.
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
        VaultNavStrike strike = navStrikeRepository.findByIdForUpdate(id).orElse(null);
        if (strike == null) {
            return new CompensationOutcome.NotApplicable("VaultNavStrike " + id + " no longer exists");
        }
        if (!effect.chainConfigId().equals(strike.getChainConfigId())
                || !BlockIdentity.sameIncarnation(
                        strike.getBlockNumber(), strike.getBlockHash(), effect.blockNumber(), effect.blockHash())
                || !BlockIdentity.sameHash(effect.txHash(), strike.getTxHash())) {
            return new CompensationOutcome.NotApplicable(
                    "VaultNavStrike " + id + " now belongs to a different block occurrence");
        }
        AssetVaultState state = vaultStateRepository.findByAssetIdForUpdate(strike.getAssetId()).orElse(null);
        var confirmedStrikes = navStrikeRepository.findByAssetIdOrderByEffectiveAtDesc(strike.getAssetId())
                .stream()
                .filter(VaultNavStrike::isConfirmed)
                .filter(s -> s.getChainConfigId() != null && s.getBlockNumber() != null && s.getBlockHash() != null)
                .toList();
        boolean ownsProjection = state != null && id.equals(state.getLatestNavStrikeId());
        long highestConfirmedStrikeId = confirmedStrikes.stream()
                .mapToLong(VaultNavStrike::getStrikeId)
                .max()
                .orElse(strike.getStrikeId());
        if (ownsProjection && highestConfirmedStrikeId != strike.getStrikeId()) {
            return new CompensationOutcome.Failed(
                    "Vault NAV projection owner is not the highest confirmed strike", null);
        }

        strike.setConfirmed(false);
        strike.setChainConfigId(null);
        strike.setBlockNumber(null);
        strike.setBlockHash(null);
        navStrikeRepository.save(strike);

        if (!ownsProjection) {
            return new CompensationOutcome.Compensated("Retracted VaultNavStrike " + id
                    + " was already superseded; its newer projection was left untouched");
        }

        VaultNavStrike previous = confirmedStrikes.stream()
                .filter(s -> !s.getId().equals(strike.getId()))
                .filter(s -> s.getStrikeId() < strike.getStrikeId())
                .max(Comparator.comparingLong(VaultNavStrike::getStrikeId))
                .orElse(null);

        log.error("VaultNavStrike id={} asset={} strikeId={} was applied to AssetVaultState but its confirming "
                        + "block was retracted by a reorg — reverting to the "
                        + "previous confirmed strike ({}).",
                id, strike.getAssetId(), strike.getStrikeId(),
                previous != null ? previous.getStrikeId() : "none — nulling out");

        if (previous != null) {
            state.setLatestNavPerShare(previous.getNavPerShare());
            state.setLatestNavStrikeAt(previous.getEffectiveAt());
            state.setLatestNavStrikeId(previous.getId());
            state.setLatestNavReportHash(previous.getReportHash());
        } else {
            state.setLatestNavPerShare(null);
            state.setLatestNavStrikeAt(null);
            state.setLatestNavStrikeId(null);
            state.setLatestNavReportHash(null);
        }
        vaultStateRepository.save(state);

        return new CompensationOutcome.Compensated("Reverted AssetVaultState for asset=" + strike.getAssetId()
                + " past retracted VaultNavStrike " + id);
    }
}
