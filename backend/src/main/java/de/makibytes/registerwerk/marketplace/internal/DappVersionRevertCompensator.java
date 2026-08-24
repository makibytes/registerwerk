package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.finality.api.BlockIdentity;
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
 * re-anchoring) and, if this was the listing's live version, takes the listing off PUBLISHED. If a
 * prior version exists in SUPERSEDED status (i.e. this publish superseded a previously-live one),
 * restores that one to PUBLISHED and back onto the listing — the pre-publish state is fully
 * reconstructible from {@code dapp_version.status} itself without needing a
 * {@code chain_effect}-carried snapshot, so there is no reason to leave the listing with no live
 * version when a perfectly good previous one is sitting right there in the same table. Falls back
 * to "no version currently live" only for a listing's very first publish, where no such version
 * exists.
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
        if (!effect.chainConfigId().equals(version.getAnchorChainConfigId())
                || !BlockIdentity.sameIncarnation(
                        version.getAnchorBlockNumber(), version.getAnchorBlockHash(),
                        effect.blockNumber(), effect.blockHash())
                || !BlockIdentity.sameHash(effect.txHash(), version.getOnchainTx())) {
            return new CompensationOutcome.NotApplicable(
                    "DappVersion " + versionId + " now belongs to a different anchor occurrence");
        }

        log.error("DappVersion id={} was PUBLISHED but its anchoring block was retracted by a reorg "
                        + "— reverting to APPROVED for re-anchoring.", versionId);
        version.setStatus(DappVersionStatus.APPROVED);
        version.setAnchorChainConfigId(null);
        version.setAnchorBlockNumber(null);
        version.setAnchorBlockHash(null);
        versionRepository.save(version);

        DappListing listing = listingRepository.findById(version.getListingId()).orElse(null);
        if (listing == null || !versionId.equals(listing.getCurrentVersionId())) {
            return new CompensationOutcome.Compensated(
                    "Reverted DappVersion " + versionId + " to APPROVED after retraction");
        }

        DappVersion previous = versionRepository.findByListingIdOrderByCreatedAtDesc(version.getListingId()).stream()
                .filter(v -> v.getStatus() == DappVersionStatus.SUPERSEDED)
                .findFirst()
                .orElse(null);
        if (previous != null) {
            log.info("DappListing id={} restoring previously-superseded DappVersion id={} to PUBLISHED "
                    + "in place of the retracted publish.", listing.getId(), previous.getId());
            previous.setStatus(DappVersionStatus.PUBLISHED);
            versionRepository.save(previous);
            listing.setCurrentVersionId(previous.getId());
            listing.setStatus(DappListingStatus.PUBLISHED);
        } else {
            listing.setStatus(DappListingStatus.APPROVED);
            listing.setCurrentVersionId(null);
        }
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);

        return new CompensationOutcome.Compensated("Reverted DappVersion " + versionId + " to APPROVED after retraction"
                + (previous != null ? "; restored previous version " + previous.getId() + " to PUBLISHED" : ""));
    }
}
