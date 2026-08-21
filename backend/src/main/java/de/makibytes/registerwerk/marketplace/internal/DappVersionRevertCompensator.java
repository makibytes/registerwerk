package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappListingStatus;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The INVERSE_FLIP compensator for {@code DAPP_VERSION_PUBLISHED} — undoes a {@link DappVersion}
 * marked PUBLISHED whose anchoring block was later retracted. Talks to
 * {@link DappVersionRepository}/{@link DappListingRepository} directly, never
 * {@code MarketplaceTxPoller} (which depends on {@code ChainEffectRecorder} — see
 * {@code blockchain.internal.tx.BlockchainTxRevertCompensator}'s javadoc for why).
 *
 * <p>Reverts the version to APPROVED (re-entering {@code MarketplaceTxPoller}'s pending set for
 * re-anchoring) and, if this was the listing's live version, takes the listing off PUBLISHED too.
 * Deliberately does <b>not</b> attempt to restore whichever version this one superseded to
 * PUBLISHED — {@code chain_effect} does not snapshot the pre-publish state (see
 * {@code ChainEffectDescriptor}'s javadoc on why pure snapshot-restore was rejected for effects
 * like this one), so "no version currently live" is the correct, honest post-compensation state
 * rather than guessing. A disclosed gap, not a silent one.
 */
@Component
class DappVersionRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "DAPP_VERSION_PUBLISHED";

    private static final Logger log = LoggerFactory.getLogger(DappVersionRevertCompensator.class);

    private final DappVersionRepository versionRepository;
    private final DappListingRepository listingRepository;

    DappVersionRevertCompensator(DappVersionRepository versionRepository, DappListingRepository listingRepository) {
        this.versionRepository = versionRepository;
        this.listingRepository = listingRepository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID versionId = effect.entityId();
        DappVersion version = versionRepository.findById(versionId).orElse(null);
        if (version == null) {
            return new CompensationOutcome.NotApplicable("DappVersion " + versionId + " no longer exists");
        }
        if (version.getStatus() != DappVersionStatus.PUBLISHED) {
            return new CompensationOutcome.NotApplicable(
                    "DappVersion " + versionId + " is no longer PUBLISHED (status=" + version.getStatus() + ")");
        }

        log.error("DappVersion id={} was PUBLISHED but its anchoring block was retracted by a reorg deep "
                        + "enough to cross FINALIZED — reverting to APPROVED for re-anchoring.", versionId);
        version.setStatus(DappVersionStatus.APPROVED);
        versionRepository.save(version);

        DappListing listing = listingRepository.findById(version.getListingId()).orElse(null);
        if (listing != null && versionId.equals(listing.getCurrentVersionId())) {
            listing.setStatus(DappListingStatus.APPROVED);
            listing.setCurrentVersionId(null);
            listing.setUpdatedAt(Instant.now());
            listingRepository.save(listing);
        }

        return new CompensationOutcome.Compensated("Reverted DappVersion " + versionId + " to APPROVED after retraction");
    }
}
