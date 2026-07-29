package de.makibytes.registerwerk.asset.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.asset.events.HolderEnteredEvent;
import de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent;
import de.makibytes.registerwerk.asset.events.HolderRemovedEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Manages asset holder records (investor positions).
 */
@Service
@Transactional
public class HolderService {

    private static final Logger log = LoggerFactory.getLogger(HolderService.class);

    private final AssetHolderRepository assetHolderRepository;
    private final de.makibytes.registerwerk.asset.api.AssetRepository assetRepository;
    private final ApplicationEventPublisher eventPublisher;

    public HolderService(AssetHolderRepository assetHolderRepository,
                         de.makibytes.registerwerk.asset.api.AssetRepository assetRepository,
                         ApplicationEventPublisher eventPublisher) {
        this.assetHolderRepository = assetHolderRepository;
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Guards that a holder's entry type is compatible with the asset's:
     * a COLLECTIVE asset cannot hold INDIVIDUAL positions and vice versa;
     * a MIXED asset (Mischbestand, §9 eWpG) permits both.
     */
    private void validateEntryTypeCompatible(
            UUID assetId, de.makibytes.registerwerk.deployment.api.EntryType holderType) {
        de.makibytes.registerwerk.deployment.api.EntryType assetType = assetRepository.findById(assetId)
                .map(de.makibytes.registerwerk.asset.api.Asset::getEntryType)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (assetType == de.makibytes.registerwerk.deployment.api.EntryType.MIXED) {
            return;
        }
        if (assetType != holderType) {
            throw new IllegalArgumentException(
                    "Holder entry type " + holderType + " is incompatible with asset entry type " + assetType);
        }
    }

    /**
     * Adds a new holder record linking an investor to a specific wallet address and nominal amount.
     */
    public AssetHolder addHolder(UUID assetId, UUID investorId, String walletAddress, BigDecimal nominalAmount,
                                  UUID actorId, String actorRole) {
        validateEntryTypeCompatible(assetId, de.makibytes.registerwerk.deployment.api.EntryType.COLLECTIVE);
        AssetHolder holder = new AssetHolder();
        holder.setAssetId(assetId);
        holder.setInvestorId(investorId);
        holder.setWalletAddress(EvmUtils.normalizeAddress(walletAddress));
        holder.setNominalAmount(nominalAmount != null ? nominalAmount : BigDecimal.ZERO);
        holder.setAcquisitionDate(LocalDate.now());
        AssetHolder saved = assetHolderRepository.save(holder);
        log.info("Added holder: assetId={}, investorId={}, wallet={}", assetId, investorId, walletAddress);
        // §19(2) no. 1: the §19 service decides whether this holder is actually
        // eligible (single entry + consumer); we signal every entry.
        eventPublisher.publishEvent(new HolderEnteredEvent(saved.getId(), actorId, actorRole));
        return saved;
    }

    /**
     * Adds a holder in single entry (Einzeleintragung, §8/§17 eWpG): the investor
     * is entered directly under a pseudonymous unique identifier (§17(2) S.2),
     * with the optional §17(2) attributes (third-party rights, disposal
     * restrictions, legal-capacity note) and a consumer flag that governs the
     * §19 statement obligation.
     *
     * <p>The pseudonymous reference is generated here and uniqueness is enforced
     * per asset by a DB constraint; a collision (astronomically unlikely) surfaces
     * as a constraint violation and the caller may retry.
     */
    public AssetHolder addSingleEntryHolder(
            UUID assetId, UUID investorId, String walletAddress, BigDecimal nominalAmount,
            boolean isConsumer, String thirdPartyRights, String disposalRestrictions,
            String legalCapacityNote, UUID actorId, String actorRole) {
        validateEntryTypeCompatible(assetId, de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL);
        AssetHolder holder = new AssetHolder();
        holder.setAssetId(assetId);
        holder.setInvestorId(investorId);
        holder.setWalletAddress(EvmUtils.normalizeAddress(walletAddress));
        holder.setNominalAmount(nominalAmount != null ? nominalAmount : BigDecimal.ZERO);
        holder.setAcquisitionDate(LocalDate.now());
        holder.setEntryType(de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL);
        holder.setHolderReference(generateHolderReference());
        holder.setIsConsumer(isConsumer);
        holder.setThirdPartyRights(thirdPartyRights);
        holder.setDisposalRestrictions(disposalRestrictions);
        holder.setLegalCapacityNote(legalCapacityNote);
        AssetHolder saved = assetHolderRepository.save(holder);
        log.info("Added single-entry holder: assetId={}, ref={}, consumer={}",
                assetId, saved.getHolderReference(), isConsumer);
        eventPublisher.publishEvent(new HolderEnteredEvent(saved.getId(), actorId, actorRole));
        return saved;
    }

    /**
     * Updates the §17(2) single-entry attributes of a holder on the instruction
     * of an authorised party (§18(1) eWpG). Records a register change so the
     * §19 statement and audit trail reflect it.
     */
    public AssetHolder updateSingleEntryAttributes(
            UUID holderId, Boolean isConsumer, String thirdPartyRights,
            String disposalRestrictions, String legalCapacityNote, UUID actorId, String actorRole) {
        AssetHolder holder = assetHolderRepository.findById(holderId)
            .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
        if (holder.getEntryType() != de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL) {
            throw new IllegalArgumentException(
                    "§17(2) attributes apply only to single-entry (Einzeleintragung) holders");
        }
        if (isConsumer != null) holder.setIsConsumer(isConsumer);
        if (thirdPartyRights != null) holder.setThirdPartyRights(thirdPartyRights);
        if (disposalRestrictions != null) holder.setDisposalRestrictions(disposalRestrictions);
        if (legalCapacityNote != null) holder.setLegalCapacityNote(legalCapacityNote);
        AssetHolder saved = assetHolderRepository.save(holder);
        eventPublisher.publishEvent(new HolderRegisterChangedEvent(saved.getId(), actorId, actorRole));
        return saved;
    }

    /**
     * Generates a pseudonymous holder reference of the form {@code RW-XXXXXXXX-XXXX}.
     * §17(2) S.2 requires only uniqueness, not a specific format; the prefix makes
     * the identifier recognisable and the random body keeps it pseudonymous
     * (it carries no personal data).
     */
    private String generateHolderReference() {
        String raw = java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return "RW-" + raw.substring(0, 8) + "-" + raw.substring(8, 12);
    }

    /**
     * Updates the nominal amount of an existing holding (e.g. after a settled
     * transfer) and signals a §19(2) no. 2 register change.
     */
    public AssetHolder updateNominalAmount(UUID holderId, BigDecimal newNominalAmount, UUID actorId, String actorRole) {
        AssetHolder holder = assetHolderRepository.findById(holderId)
            .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
        holder.setNominalAmount(newNominalAmount != null ? newNominalAmount : BigDecimal.ZERO);
        AssetHolder saved = assetHolderRepository.save(holder);
        eventPublisher.publishEvent(new HolderRegisterChangedEvent(saved.getId(), actorId, actorRole));
        return saved;
    }

    /**
     * Removes a holder record. Soft-delete: a §16 eWpG register entry must not simply
     * disappear (retention/tamper-evidence obligations), so this sets {@code removedAt}
     * rather than issuing a hard {@code DELETE} — the row, and its history, remain in the
     * table but are excluded from compliance-facing reads (see
     * {@code AssetHolderRepository.findActive*}).
     */
    public void removeHolder(UUID holderId, UUID actorId, String actorRole) {
        AssetHolder holder = assetHolderRepository.findById(holderId)
            .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
        holder.setRemovedAt(Instant.now());
        assetHolderRepository.save(holder);
        log.info("Removed holder: holderId={}, by={}", holderId, actorId);
        eventPublisher.publishEvent(new HolderRemovedEvent(holderId, actorId, actorRole));
    }

    @Transactional(readOnly = true)
    public Page<AssetHolder> listHolders(UUID assetId, Pageable pageable) {
        // Compliance-facing holder list: a removed holder is no longer part of the register.
        return assetHolderRepository.findActiveByAssetId(assetId, pageable);
    }

    @Transactional(readOnly = true)
    public AssetHolder getHolder(UUID holderId) {
        return assetHolderRepository.findById(holderId)
            .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<AssetHolder> findByAssetIdAndWalletAddress(UUID assetId, String walletAddress) {
        // Active-only: used by LiveHolderService to attribute on-chain balances to a register
        // identity. A wallet whose only prior link was a removed holder should surface as
        // "unknown", not silently resurrect the closed-out register entry.
        return assetHolderRepository.findActiveByAssetIdAndWalletAddress(assetId, EvmUtils.normalizeAddress(walletAddress));
    }
}
