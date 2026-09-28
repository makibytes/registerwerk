package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.HolderRemovedEvent;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.registertransfer.api.PortfolioMigrationRequest;
import de.makibytes.registerwerk.registertransfer.api.PortfolioMigrationRequestRepository;
import de.makibytes.registerwerk.registertransfer.api.TransferStatus;
import de.makibytes.registerwerk.registertransfer.events.PortfolioMigrationEvent;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages portfolio-migration requests — the investor-side, per-holding counterpart to
 * {@link RegisterTransferService} (which is asset-side). Previously an investor leaving the
 * platform had NO path to move their holding to a successor/competitor registrar at all; only
 * the asset's own register/on-chain-control handover existed.
 *
 * <p>Phase 3 (K2) controls:
 * <ul>
 *   <li><b>Verified handover (T3-17).</b> The recorded on-chain transfer must match a FINALIZED
 *       indexed {@code token_transfer} of that tx on one of the asset's deployments, from the
 *       holder's wallet to the destination wallet, for exactly the holder's nominal. Where no
 *       indexed deployment exists (off-chain register; Solana/Canton until Phase 4) an operator
 *       attestation is required instead (the endpoint is step-up + 4-eyes).</li>
 *   <li><b>Frozen destination (T3-17).</b> The destination is editable only while INITIATED;
 *       after export the package would no longer describe the handover.</li>
 *   <li><b>Successor custody wallet (T3-17, interim pending PARK).</b> A verified migration moves
 *       the units to a wallet on the same contract; {@code complete} refuses until that wallet has
 *       an active register row (e.g. a NOMINEE_POOL entry), otherwise the holder sync would BLOCK
 *       the whole asset.</li>
 *   <li><b>§17(2) content and blocks (T3-07 C-05b).</b> The export package carries the entry's
 *       §17(2) attributes and its ACTIVE HolderBlocks; an entry with third-party rights or
 *       disposal restrictions migrates only with a beneficiary consent reference.</li>
 * </ul>
 */
@Service
public class PortfolioMigrationService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioMigrationService.class);
    private static final List<TransferStatus> TERMINAL = List.of(TransferStatus.COMPLETED, TransferStatus.CANCELLED);

    /** Chains whose transfers are not yet indexed with a deployment id (T3-18, Phase 4). */
    private static final Set<Chain> UNINDEXED_CHAINS = EnumSet.of(Chain.SOLANA, Chain.CANTON);

    private final PortfolioMigrationRequestRepository repository;
    private final AssetHolderRepository holderRepository;
    private final AssetRepository assetRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final HolderBlockGate holderBlockGate;
    private final FinalityGate finalityGate;
    private final AssetDeploymentRepository deploymentRepository;
    private final TokenTransferRepository tokenTransferRepository;
    private final HolderBlockRepository holderBlockRepository;

    public PortfolioMigrationService(PortfolioMigrationRequestRepository repository,
                                      AssetHolderRepository holderRepository,
                                      AssetRepository assetRepository,
                                      ObjectMapper objectMapper,
                                      ApplicationEventPublisher eventPublisher,
                                      HolderBlockGate holderBlockGate,
                                      FinalityGate finalityGate,
                                      AssetDeploymentRepository deploymentRepository,
                                      TokenTransferRepository tokenTransferRepository,
                                      HolderBlockRepository holderBlockRepository) {
        this.repository = repository;
        this.holderRepository = holderRepository;
        this.assetRepository = assetRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.holderBlockGate = holderBlockGate;
        this.finalityGate = finalityGate;
        this.deploymentRepository = deploymentRepository;
        this.tokenTransferRepository = tokenTransferRepository;
        this.holderBlockRepository = holderBlockRepository;
    }

    /** Initiates a migration without a beneficiary consent reference (see the 4-arg overload). */
    @Transactional
    public PortfolioMigrationRequest initiate(UUID holderId, String reason, UUID initiatedBy) {
        return initiate(holderId, reason, null, initiatedBy);
    }

    /**
     * Initiates a migration for one holding. Rejects a second concurrent request for the same
     * holding. Fail-closed §16 eWpG Sperrvermerk check — the earliest possible point to refuse
     * a legally blocked holding, since a portfolio migration moves custody to a successor/
     * competitor registrar entirely, potentially exiting this registry's compliance oversight
     * before a court-ordered freeze would otherwise be noticed. An entry with third-party rights
     * or disposal restrictions additionally needs {@code beneficiaryConsentRef} (T3-07 C-05b).
     */
    @Transactional
    public PortfolioMigrationRequest initiate(UUID holderId, String reason, String beneficiaryConsentRef,
                                              UUID initiatedBy) {
        AssetHolder holder = holderRepository.findById(holderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset holder " + holderId));
        if (repository.existsByHolderIdAndStatusNotIn(holderId, TERMINAL)) {
            throw new IllegalStateException("A portfolio migration for holder " + holderId + " is already in progress");
        }
        requireNotBlocked(holder);
        requireRightsConsent(holder, beneficiaryConsentRef);

        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setInvestorEntityId(holder.getInvestorId());
        migration.setAssetId(holder.getAssetId());
        migration.setHolderId(holderId);
        migration.setReason(reason);
        migration.setInitiatedBy(initiatedBy);
        migration.setBeneficiaryConsentRef(blankToNull(beneficiaryConsentRef));
        migration.setStatus(TransferStatus.INITIATED);
        PortfolioMigrationRequest saved = repository.save(migration);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("holderId", holderId);
        details.put("investorEntityId", holder.getInvestorId());
        details.put("reason", reason);
        if (saved.getBeneficiaryConsentRef() != null) {
            details.put("beneficiaryConsentRef", saved.getBeneficiaryConsentRef());
        }
        eventPublisher.publishEvent(new PortfolioMigrationEvent(saved.getId(), "INITIATED", initiatedBy, "REGISTRY_ADMIN",
                details));
        return saved;
    }

    /**
     * Records the destination (operator must supply this — cannot be inferred). Only while
     * INITIATED (T3-17): the export package and its hash describe this destination, and the
     * on-chain verification checks the transfer against it.
     */
    @Transactional
    public PortfolioMigrationRequest setDestination(UUID migrationId, String registrarName, String registrarIdentifier,
                                                     String walletAddress, UUID actorId) {
        PortfolioMigrationRequest migration = load(migrationId);
        require(migration.getStatus() == TransferStatus.INITIATED,
                "The destination can only be changed before export (status is " + migration.getStatus()
                        + "); cancel this migration and initiate a new one");
        migration.setDestinationRegistrarName(registrarName);
        migration.setDestinationRegistrarIdentifier(registrarIdentifier);
        migration.setDestinationWalletAddress(walletAddress);
        migration.setUpdatedAt(Instant.now());
        return repository.save(migration);
    }

    /** Builds and records the holding's data-export package. Returns the canonical JSON bytes. */
    @Transactional
    public byte[] export(UUID migrationId, UUID actorId) {
        PortfolioMigrationRequest migration = load(migrationId);
        require(migration.getStatus() == TransferStatus.INITIATED || migration.getStatus() == TransferStatus.EXPORTED,
                "Only an INITIATED or already-EXPORTED migration can be (re-)exported");
        require(migration.getDestinationWalletAddress() != null,
                "Destination wallet must be set before exporting (see setDestination)");

        AssetHolder holder = holderRepository.findById(migration.getHolderId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset holder " + migration.getHolderId()));
        Asset asset = assetRepository.findById(migration.getAssetId()).orElse(null);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", "1.1");
        manifest.put("standard", "Portfolio migration — investor holding export");
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("investorEntityId", migration.getInvestorEntityId());
        manifest.put("destinationRegistrarName", migration.getDestinationRegistrarName());
        manifest.put("destinationWalletAddress", migration.getDestinationWalletAddress());
        manifest.put("beneficiaryConsentRef", migration.getBeneficiaryConsentRef());
        manifest.put("asset", assetSnapshot(asset));
        manifest.put("holding", holderSnapshot(holder));
        manifest.put("holderBlocks", activeBlocks(holder));

        byte[] json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise portfolio migration export", e);
        }

        migration.setExportManifest(manifest);
        migration.setExportHash(sha256Hex(json));
        migration.setStatus(TransferStatus.EXPORTED);
        migration.setExportedAt(Instant.now());
        migration.setUpdatedAt(Instant.now());
        repository.save(migration);
        log.info("Exported portfolio migration {} for holder {} ({} bytes)", migrationId, migration.getHolderId(), json.length);

        eventPublisher.publishEvent(new PortfolioMigrationEvent(migrationId, "EXPORTED", actorId, "REGISTRY_ADMIN",
                Map.of("exportHash", migration.getExportHash(), "bytes", json.length)));
        return json;
    }

    /**
     * Records the on-chain transfer of the holding to the destination wallet. Re-checks
     * {@link HolderBlockGate} and the §17(2) rights consent as defense-in-depth against a block or
     * right applied mid-flight, between {@link #initiate} and this (potentially much later) step.
     *
     * <p>T3-17: the tx is verified against indexed FINALIZED data (see the class javadoc) — all of
     * tx hash, deployment, from, to and amount must match. Only when the asset has no indexed
     * deployment is {@code operatorAttestation} accepted in its place.
     */
    @Transactional
    public PortfolioMigrationRequest recordOnchainTransfer(UUID migrationId, String txHash, String operatorAttestation,
                                                           String beneficiaryConsentRef, UUID actorId) {
        PortfolioMigrationRequest migration = load(migrationId);
        require(migration.getStatus() == TransferStatus.EXPORTED, "Export must precede the on-chain transfer");
        AssetHolder holder = holderRepository.findById(migration.getHolderId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset holder " + migration.getHolderId()));
        requireNotBlocked(holder);
        if (!isBlank(beneficiaryConsentRef)) {
            migration.setBeneficiaryConsentRef(beneficiaryConsentRef.trim());
        }
        requireRightsConsent(holder, migration.getBeneficiaryConsentRef());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("txHash", txHash);
        List<UUID> indexedDeployments = indexedDeploymentIds(migration.getAssetId());
        if (indexedDeployments.isEmpty()) {
            require(!isBlank(operatorAttestation), "Asset " + migration.getAssetId() + " has no indexed deployment to "
                    + "verify the handover against; an operator attestation of the transfer is required");
            migration.setOperatorAttestation(operatorAttestation.trim());
            details.put("verification", "OPERATOR_ATTESTATION");
            details.put("operatorAttestation", migration.getOperatorAttestation());
        } else {
            TokenTransfer verified = findMatchingTransfer(txHash, indexedDeployments, holder,
                    migration.getDestinationWalletAddress())
                    .orElseThrow(() -> new IllegalStateException("No FINALIZED indexed transfer in tx " + txHash
                            + " moves " + plain(holder) + " units of asset " + migration.getAssetId() + " from "
                            + holder.getWalletAddress() + " to " + migration.getDestinationWalletAddress()
                            + ". Wait until the transfer is indexed and final, or check the tx hash, wallets and amount."));
            migration.setOperatorAttestation(null);
            details.put("verification", "INDEXED_TRANSFER");
            details.put("tokenTransferId", verified.getId());
            details.put("deploymentId", verified.getDeploymentId());
        }
        if (migration.getBeneficiaryConsentRef() != null) {
            details.put("beneficiaryConsentRef", migration.getBeneficiaryConsentRef());
        }
        migration.setOnchainTxHash(txHash);
        migration.setStatus(TransferStatus.HANDED_OVER);
        migration.setUpdatedAt(Instant.now());
        PortfolioMigrationRequest saved = repository.save(migration);

        eventPublisher.publishEvent(new PortfolioMigrationEvent(migrationId, "HANDED_OVER", actorId, "REGISTRY_ADMIN",
                details));
        return saved;
    }

    /**
     * Marks the migration complete once the destination registrar has confirmed receipt, and
     * closes out the source register entry — flipping only the migration request's own status
     * would leave the underlying {@code AssetHolder} row untouched, so a "completed" migration
     * would leave the investor showing as an active, full-balance holder at the source
     * registrar forever (double-register risk: still eligible for future coupon/redemption
     * entitlements and register statements here despite the position having legally moved to
     * the successor registrar).
     */
    @Transactional
    public PortfolioMigrationRequest complete(UUID migrationId, UUID actorId) {
        PortfolioMigrationRequest migration = load(migrationId);
        require(migration.getStatus() == TransferStatus.HANDED_OVER, "Only a HANDED_OVER migration can be completed");

        // Hard floor, same reasoning as RegisterTransferService.complete()'s REGISTER_TRANSFER_COMPLETE
        // gate — this is the point of no return for one holding's register entry.
        assetRepository.findById(migration.getAssetId()).ifPresent(asset ->
                finalityGate.require(GatedOperation.PORTFOLIO_MIGRATION_COMPLETE, migration.getAssetId(),
                        asset.getTokenStandard(), FinalityLevel.FINALIZED));

        // T3-17 interim (PARK: successor custody wallet): a verified handover moved the units to a
        // wallet on the same contract. Without an active register row for it the next holder sync
        // BLOCKS the whole asset, so the operator registers it (e.g. as NOMINEE_POOL) first.
        if (migration.getOperatorAttestation() == null && migration.getDestinationWalletAddress() != null) {
            String destination = migration.getDestinationWalletAddress();
            boolean registered = holderRepository.findActiveByAssetId(migration.getAssetId()).stream()
                    .anyMatch(h -> sameAddress(h.getWalletAddress(), destination));
            require(registered, "Destination wallet " + destination + " holds the migrated units on this asset's "
                    + "contract but has no active register entry; register it (e.g. as a NOMINEE_POOL entry of the "
                    + "successor registrar) before completing, otherwise the holder sync blocks the asset");
        }

        migration.setStatus(TransferStatus.COMPLETED);
        migration.setCompletedAt(Instant.now());
        migration.setUpdatedAt(Instant.now());
        PortfolioMigrationRequest saved = repository.save(migration);

        Optional<AssetHolder> holder = holderRepository.findById(migration.getHolderId());
        if (holder.isPresent()) {
            holder.get().setRemovedAt(Instant.now());
            holderRepository.save(holder.get());
            eventPublisher.publishEvent(new HolderRemovedEvent(holder.get().getId(), actorId, "REGISTRY_ADMIN"));
        } else {
            log.warn("Holder {} disappeared before its migrated-out register entry could be closed (migration={})",
                    migration.getHolderId(), migrationId);
        }

        eventPublisher.publishEvent(new PortfolioMigrationEvent(migrationId, "COMPLETED", actorId, "REGISTRY_ADMIN", Map.of()));
        return saved;
    }

    @Transactional
    public PortfolioMigrationRequest cancel(UUID migrationId, String reason, UUID actorId) {
        PortfolioMigrationRequest migration = load(migrationId);
        require(migration.getStatus() != TransferStatus.COMPLETED, "A completed migration cannot be cancelled");
        migration.setStatus(TransferStatus.CANCELLED);
        migration.setReason(migration.getReason() + " | cancelled: " + reason);
        migration.setUpdatedAt(Instant.now());
        PortfolioMigrationRequest saved = repository.save(migration);

        eventPublisher.publishEvent(new PortfolioMigrationEvent(migrationId, "CANCELLED", actorId, "REGISTRY_ADMIN",
                Map.of("reason", reason)));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PortfolioMigrationRequest> listForInvestor(UUID investorEntityId) {
        return repository.findByInvestorEntityIdOrderByInitiatedAtDesc(investorEntityId);
    }

    private List<UUID> indexedDeploymentIds(UUID assetId) {
        return deploymentRepository.findByAssetId(assetId).stream()
                .filter(d -> d.getChain() == null || !UNINDEXED_CHAINS.contains(d.getChain()))
                .map(AssetDeployment::getId)
                .toList();
    }

    private Optional<TokenTransfer> findMatchingTransfer(String txHash, List<UUID> deploymentIds, AssetHolder holder,
                                                         String destinationWallet) {
        return tokenTransferRepository.findByTxHashIgnoreCaseAndFinalityStatus(txHash, FinalityLevel.FINALIZED).stream()
                .filter(t -> t.getDeploymentId() != null && deploymentIds.contains(t.getDeploymentId()))
                .filter(t -> sameAddress(t.getFromAddress(), holder.getWalletAddress()))
                .filter(t -> sameAddress(t.getToAddress(), destinationWallet))
                .filter(t -> t.getAmount() != null && holder.getNominalAmount() != null
                        && t.getAmount().compareTo(holder.getNominalAmount()) == 0)
                .findFirst();
    }

    private Map<String, Object> assetSnapshot(Asset asset) {
        if (asset == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", asset.getId());
        m.put("name", asset.getName());
        m.put("isin", asset.getIsin());
        m.put("tokenStandard", asset.getTokenStandard() != null ? asset.getTokenStandard().name() : null);
        return m;
    }

    /** The register entry including its §17(2) eWpG content (T3-07 C-05b). */
    private Map<String, Object> holderSnapshot(AssetHolder h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("walletAddress", h.getWalletAddress());
        m.put("nominalAmount", h.getNominalAmount() != null ? h.getNominalAmount().toPlainString() : "0");
        m.put("acquisitionDate", h.getAcquisitionDate() != null ? h.getAcquisitionDate().toString() : null);
        m.put("entryType", h.getEntryType() != null ? h.getEntryType().name() : null);
        m.put("holderKind", h.getHolderKind() != null ? h.getHolderKind().name() : null);
        m.put("holderReference", h.getHolderReference());
        m.put("isConsumer", h.getIsConsumer());
        m.put("thirdPartyRights", h.getThirdPartyRights());
        m.put("disposalRestrictions", h.getDisposalRestrictions());
        m.put("legalCapacityNote", h.getLegalCapacityNote());
        return m;
    }

    /** ACTIVE HolderBlocks that apply to this entry: on the investor (entity-wide or this asset) or its wallet. */
    private List<Map<String, Object>> activeBlocks(AssetHolder holder) {
        Map<UUID, HolderBlock> blocks = new LinkedHashMap<>();
        holderBlockRepository.findByEntityIdAndStatus(holder.getInvestorId(), HolderBlock.Status.ACTIVE).stream()
                .filter(b -> b.getAssetId() == null || b.getAssetId().equals(holder.getAssetId()))
                .forEach(b -> blocks.putIfAbsent(b.getId(), b));
        if (holder.getWalletAddress() != null) {
            List<String> spellings = new ArrayList<>(List.of(holder.getWalletAddress()));
            String normalized = de.makibytes.registerwerk.blockchain.api.EvmUtils.normalizeAddress(holder.getWalletAddress());
            if (!spellings.contains(normalized)) {
                spellings.add(normalized);
            }
            for (String wallet : spellings) {
                holderBlockRepository.findByWalletAddressAndStatus(wallet, HolderBlock.Status.ACTIVE).stream()
                        .filter(b -> b.getAssetId() == null || b.getAssetId().equals(holder.getAssetId()))
                        .forEach(b -> blocks.putIfAbsent(b.getId(), b));
            }
        }
        return blocks.values().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("blockType", b.getBlockType() != null ? b.getBlockType().name() : null);
            m.put("scope", b.getAssetId() != null ? "ASSET" : "ENTITY");
            m.put("walletAddress", b.getWalletAddress());
            m.put("legalBasis", b.getLegalBasis());
            m.put("courtRef", b.getCourtRef());
            m.put("documentId", b.getDocumentId());
            m.put("startsAt", b.getStartsAt() != null ? b.getStartsAt().toString() : null);
            m.put("expiresAt", b.getExpiresAt() != null ? b.getExpiresAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
    }

    private PortfolioMigrationRequest load(UUID migrationId) {
        return repository.findById(migrationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown portfolio migration " + migrationId));
    }

    private void requireNotBlocked(AssetHolder holder) {
        if (holderBlockGate.isBlocked(holder.getInvestorId(), holder.getWalletAddress())) {
            throw new ComplianceGateException(
                    "Holder " + holder.getId() + " is subject to an active §16 eWpG Sperrvermerk "
                    + "(legal block) — portfolio migration refused.");
        }
    }

    /**
     * T3-07 (C-05b): third-party rights and disposal restrictions (§17(2) eWpG) are not legal
     * blocks, so {@link HolderBlockGate} does not see them — but moving the entry to another
     * registrar affects the beneficiary, whose consent must be on record.
     */
    private static void requireRightsConsent(AssetHolder holder, String beneficiaryConsentRef) {
        boolean encumbered = !isBlank(holder.getThirdPartyRights()) || !isBlank(holder.getDisposalRestrictions());
        if (encumbered && isBlank(beneficiaryConsentRef)) {
            throw new ComplianceGateException("Holder " + holder.getId() + " carries third-party rights or disposal "
                    + "restrictions (§17(2) eWpG) — portfolio migration requires the beneficiary's consent reference.");
        }
    }

    private static boolean sameAddress(String a, String b) {
        return a != null && b != null && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    private static String plain(AssetHolder holder) {
        return holder.getNominalAmount() != null ? holder.getNominalAmount().toPlainString() : "0";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return "0x" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
