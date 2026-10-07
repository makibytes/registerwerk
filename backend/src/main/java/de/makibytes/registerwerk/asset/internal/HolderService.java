package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.shared.RegisterClock;
import de.makibytes.registerwerk.asset.api.RegisterFreezeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
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
    private final LegalEntityRepository legalEntityRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final HolderChangeRepository changeRepository;
    private final RegisterClock registerClock;

    public HolderService(AssetHolderRepository assetHolderRepository,
                         de.makibytes.registerwerk.asset.api.AssetRepository assetRepository,
                         ApplicationEventPublisher eventPublisher,
                         LegalEntityRepository legalEntityRepository,
                         AssetDeploymentRepository deploymentRepository,
                         HolderChangeRepository changeRepository,
                         RegisterClock registerClock) {
        this.assetHolderRepository = assetHolderRepository;
        this.registerClock = registerClock;
        this.assetRepository = assetRepository;
        this.eventPublisher = eventPublisher;
        this.legalEntityRepository = legalEntityRepository;
        this.deploymentRepository = deploymentRepository;
        this.changeRepository = changeRepository;
    }

    /** True when the asset has a CONFIRMED on-chain deployment (the chain is then the source of balances). */
    boolean hasConfirmedDeployment(UUID assetId) {
        return deploymentRepository.findByAssetId(assetId).stream()
                .anyMatch(d -> d.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED);
    }

    /**
     * T3-13: a manual register entry needs a real, KYC-approved investor entity, and on a chain-deployed
     * asset it is only a wallet mapping (nominal 0) — the holder sync sets the balance from the chain.
     */
    private void requireManualEntryAllowed(UUID assetId, UUID investorId, BigDecimal nominalAmount) {
        LegalEntity investor = legalEntityRepository.findById(investorId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown investor entity " + investorId));
        if (investor.getKycStatus() != KycStatus.APPROVED) {
            throw new IllegalArgumentException("Investor " + investorId + " does not have an approved KYC status "
                    + "(current: " + investor.getKycStatus() + ") and cannot be entered in the register");
        }
        if (nominalAmount != null && nominalAmount.signum() != 0 && hasConfirmedDeployment(assetId)) {
            throw new IllegalArgumentException("This asset is deployed on-chain: a manual register entry must have "
                    + "nominalAmount 0 (a wallet mapping); the balance is set by the holder sync from the chain");
        }
    }

    private void requireWalletFree(UUID assetId, String normalizedWallet) {
        if (assetHolderRepository.findActiveByAssetIdAndWalletAddress(assetId, normalizedWallet).isPresent()) {
            throw new InvalidStateTransitionException(
                    "Wallet " + normalizedWallet + " already has an active register entry on this asset");
        }
    }

    private void recordChange(AssetHolder holder, HolderChange.ChangeType type, HolderInstruction ins,
                              Map<String, Object> before, Map<String, Object> after, UUID actorId, String actorRole) {
        HolderChange change = new HolderChange();
        change.setAssetId(holder.getAssetId());
        change.setHolderId(holder.getId());
        change.setChangeType(type);
        change.setInstructingParty(ins.party());
        change.setInstructionReference(ins.reference());
        change.setBeforeState(before);
        change.setAfterState(after);
        change.setActorId(actorId);
        change.setActorRole(actorRole);
        change.setApproverId(ins.approverId());
        change.setChangeRequestId(ins.changeRequestId());
        changeRepository.save(change);
    }

    private static Map<String, Object> auditDetails(HolderInstruction ins, Map<String, Object> before,
                                                    Map<String, Object> after) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("instructingParty", ins.party().name());
        d.put("instructionReference", ins.reference());
        if (ins.approverId() != null) d.put("approverId", ins.approverId().toString());
        if (ins.changeRequestId() != null) d.put("changeRequestId", ins.changeRequestId().toString());
        d.put("before", before);
        d.put("after", after);
        return d;
    }

    private static Map<String, Object> attributeState(AssetHolder h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nominalAmount", h.getNominalAmount() != null ? h.getNominalAmount().toPlainString() : null);
        m.put("isConsumer", h.getIsConsumer());
        m.put("thirdPartyRights", h.getThirdPartyRights());
        m.put("disposalRestrictions", h.getDisposalRestrictions());
        m.put("legalCapacityNote", h.getLegalCapacityNote());
        return m;
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
     * Adds a new holder record linking an investor to a specific wallet address and nominal amount,
     * on the recorded instruction of an authorised party (T3-13; operator-executed).
     */
    public AssetHolder addHolder(UUID assetId, UUID investorId, String walletAddress, BigDecimal nominalAmount,
                                  HolderInstruction instruction, UUID actorId, String actorRole) {
        instruction.validate();
        RegisterFreezeGuard.requireOpen(assetRepository, assetId, "Adding a register entry");
        validateEntryTypeCompatible(assetId, de.makibytes.registerwerk.deployment.api.EntryType.COLLECTIVE);
        requireManualEntryAllowed(assetId, investorId, nominalAmount);
        String wallet = EvmUtils.normalizeAddress(walletAddress);
        requireWalletFree(assetId, wallet);
        AssetHolder holder = new AssetHolder();
        holder.setAssetId(assetId);
        holder.setInvestorId(investorId);
        holder.setWalletAddress(wallet);
        holder.setNominalAmount(nominalAmount != null ? nominalAmount : BigDecimal.ZERO);
        holder.setAcquisitionDate(registerClock.today());
        AssetHolder saved = assetHolderRepository.save(holder);
        log.info("Added holder: assetId={}, investorId={}, wallet={}", assetId, investorId, walletAddress);
        Map<String, Object> after = attributeState(saved);
        recordChange(saved, HolderChange.ChangeType.CREATED, instruction, null, after, actorId, actorRole);
        // §19(2) no. 1: the §19 service decides whether this holder is actually
        // eligible (single entry + consumer); we signal every entry.
        eventPublisher.publishEvent(new HolderEnteredEvent(saved.getId(), actorId, actorRole, null,
                auditDetails(instruction, Map.of(), after)));
        return saved;
    }

    /**
     * Credits {@code amount} to an investor's position on an asset without an on-chain leg (T3-08
     * primary subscription on a not-deployed asset): increments the existing active row of
     * investor + wallet, or inserts one (single-entry for INDIVIDUAL assets). Never inserts a second
     * row for a wallet that is already active, so a top-up cannot hit the unique wallet index.
     */
    public AssetHolder creditPosition(UUID assetId, UUID investorId, String walletAddress, BigDecimal amount,
                                      boolean consumer, UUID actorId, String actorRole, UUID correlationId) {
        RegisterFreezeGuard.requireOpen(assetRepository, assetId, "Crediting a register position");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        String wallet = EvmUtils.normalizeAddress(walletAddress);
        java.util.Optional<AssetHolder> existing = assetHolderRepository.findActiveByAssetIdAndWalletAddress(assetId, wallet);
        if (existing.isPresent()) {
            AssetHolder holder = existing.get();
            if (!holder.getInvestorId().equals(investorId)) {
                throw new InvalidStateTransitionException(
                        "Wallet " + wallet + " is registered to a different investor on this asset");
            }
            BigDecimal before = holder.getNominalAmount();
            holder.setNominalAmount(before.add(amount));
            AssetHolder saved = assetHolderRepository.save(holder);
            eventPublisher.publishEvent(new HolderRegisterChangedEvent(saved.getId(), actorId, actorRole, correlationId));
            return saved;
        }
        de.makibytes.registerwerk.deployment.api.EntryType assetType = assetRepository.findById(assetId)
                .map(de.makibytes.registerwerk.asset.api.Asset::getEntryType)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        boolean individual = assetType == de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL;
        validateEntryTypeCompatible(assetId, individual
                ? de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL
                : de.makibytes.registerwerk.deployment.api.EntryType.COLLECTIVE);
        AssetHolder holder = new AssetHolder();
        holder.setAssetId(assetId);
        holder.setInvestorId(investorId);
        holder.setWalletAddress(wallet);
        holder.setNominalAmount(amount);
        holder.setAcquisitionDate(registerClock.today());
        if (individual) {
            holder.setEntryType(de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL);
            holder.setHolderReference(generateHolderReference());
            holder.setIsConsumer(consumer);
        }
        AssetHolder saved = assetHolderRepository.save(holder);
        eventPublisher.publishEvent(new HolderEnteredEvent(saved.getId(), actorId, actorRole, correlationId));
        return saved;
    }

    /**
     * Makes sure the wallet of a subscribed investor has an active register row on a chain-deployed
     * asset (T3-08): a mapping entry with nominal 0 whose balance the holder sync sets from the mint.
     * An existing active row of another investor is refused; a removed row is left as history and a
     * fresh row is inserted.
     */
    public AssetHolder ensureMappingRow(UUID assetId, UUID investorId, String walletAddress,
                                        UUID actorId, String actorRole, UUID correlationId) {
        RegisterFreezeGuard.requireOpen(assetRepository, assetId, "Mapping a register wallet");
        String wallet = EvmUtils.normalizeAddress(walletAddress);
        java.util.Optional<AssetHolder> existing = assetHolderRepository.findActiveByAssetIdAndWalletAddress(assetId, wallet);
        if (existing.isPresent()) {
            if (!existing.get().getInvestorId().equals(investorId)) {
                throw new InvalidStateTransitionException(
                        "Wallet " + wallet + " is registered to a different investor on this asset");
            }
            return existing.get();
        }
        AssetHolder holder = new AssetHolder();
        holder.setAssetId(assetId);
        holder.setInvestorId(investorId);
        holder.setWalletAddress(wallet);
        holder.setNominalAmount(BigDecimal.ZERO);
        holder.setAcquisitionDate(registerClock.today());
        AssetHolder saved = assetHolderRepository.save(holder);
        eventPublisher.publishEvent(new HolderEnteredEvent(saved.getId(), actorId, actorRole, correlationId));
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
            String legalCapacityNote, HolderInstruction instruction, UUID actorId, String actorRole) {
        instruction.validate();
        RegisterFreezeGuard.requireOpen(assetRepository, assetId, "Adding a register entry");
        validateEntryTypeCompatible(assetId, de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL);
        requireManualEntryAllowed(assetId, investorId, nominalAmount);
        String wallet = EvmUtils.normalizeAddress(walletAddress);
        requireWalletFree(assetId, wallet);
        AssetHolder holder = new AssetHolder();
        holder.setAssetId(assetId);
        holder.setInvestorId(investorId);
        holder.setWalletAddress(wallet);
        holder.setNominalAmount(nominalAmount != null ? nominalAmount : BigDecimal.ZERO);
        holder.setAcquisitionDate(registerClock.today());
        holder.setEntryType(de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL);
        holder.setHolderReference(generateHolderReference());
        holder.setIsConsumer(isConsumer);
        holder.setThirdPartyRights(thirdPartyRights);
        holder.setDisposalRestrictions(disposalRestrictions);
        holder.setLegalCapacityNote(legalCapacityNote);
        AssetHolder saved = assetHolderRepository.save(holder);
        log.info("Added single-entry holder: assetId={}, ref={}, consumer={}",
                assetId, saved.getHolderReference(), isConsumer);
        Map<String, Object> after = attributeState(saved);
        recordChange(saved, HolderChange.ChangeType.CREATED, instruction, null, after, actorId, actorRole);
        eventPublisher.publishEvent(new HolderEnteredEvent(saved.getId(), actorId, actorRole, null,
                auditDetails(instruction, Map.of(), after)));
        return saved;
    }

    /**
     * The requested change to a holder's §17(2) attributes. A blank text means "no change"; clearing a
     * value needs the explicit {@code clear*} flag (T3-13), so a stray empty field cannot wipe a right.
     */
    public record AttributeChange(Boolean isConsumer, String thirdPartyRights, String disposalRestrictions,
                                  String legalCapacityNote, boolean clearThirdPartyRights,
                                  boolean clearDisposalRestrictions) {
    }

    /**
     * Updates the §17(2) single-entry attributes of a holder on the instruction of an authorised party
     * (§18(1) eWpG), executed by the operator. Records the instruction and the before/after values in
     * {@code asset_holder_change} and on the register-change event.
     */
    public AssetHolder updateSingleEntryAttributes(
            UUID assetId, UUID holderId, AttributeChange change, HolderInstruction instruction,
            UUID actorId, String actorRole) {
        instruction.validate();
        RegisterFreezeGuard.requireOpen(assetRepository, assetId, "Changing a register entry");
        AssetHolder holder = assetHolderRepository.findByIdAndAssetId(holderId, assetId)
            .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
        if (holder.getEntryType() != de.makibytes.registerwerk.deployment.api.EntryType.INDIVIDUAL) {
            throw new IllegalArgumentException(
                    "§17(2) attributes apply only to single-entry (Einzeleintragung) holders");
        }
        if (change.clearThirdPartyRights() && !isBlank(change.thirdPartyRights())) {
            throw new IllegalArgumentException("thirdPartyRights and clearThirdPartyRights are mutually exclusive");
        }
        if (change.clearDisposalRestrictions() && !isBlank(change.disposalRestrictions())) {
            throw new IllegalArgumentException("disposalRestrictions and clearDisposalRestrictions are mutually exclusive");
        }
        Map<String, Object> before = attributeState(holder);
        boolean rights = false;
        if (change.isConsumer() != null) holder.setIsConsumer(change.isConsumer());
        if (change.clearThirdPartyRights()) {
            holder.setThirdPartyRights(null);
            rights = true;
        } else if (!isBlank(change.thirdPartyRights())) {
            holder.setThirdPartyRights(change.thirdPartyRights());
            rights = true;
        }
        if (change.clearDisposalRestrictions()) {
            holder.setDisposalRestrictions(null);
            rights = true;
        } else if (!isBlank(change.disposalRestrictions())) {
            holder.setDisposalRestrictions(change.disposalRestrictions());
            rights = true;
        }
        if (!isBlank(change.legalCapacityNote())) holder.setLegalCapacityNote(change.legalCapacityNote());
        Map<String, Object> after = attributeState(holder);
        if (before.equals(after)) {
            throw new IllegalArgumentException("The request changes nothing (blank text means 'no change'; "
                    + "use clearThirdPartyRights / clearDisposalRestrictions to remove a value)");
        }
        AssetHolder saved = assetHolderRepository.save(holder);
        recordChange(saved, rights ? HolderChange.ChangeType.RIGHTS_CHANGED : HolderChange.ChangeType.ATTRIBUTES_CHANGED,
                instruction, before, after, actorId, actorRole);
        eventPublisher.publishEvent(new HolderRegisterChangedEvent(saved.getId(), actorId, actorRole, null,
                auditDetails(instruction, before, after)));
        return saved;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
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
    public void removeHolder(UUID assetId, UUID holderId, UUID actorId, String actorRole) {
        RegisterFreezeGuard.requireOpen(assetRepository, assetId, "Removing a register entry");
        AssetHolder holder = assetHolderRepository.findByIdAndAssetId(holderId, assetId)
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
    public AssetHolder getHolder(UUID assetId, UUID holderId) {
        return assetHolderRepository.findByIdAndAssetId(holderId, assetId)
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
