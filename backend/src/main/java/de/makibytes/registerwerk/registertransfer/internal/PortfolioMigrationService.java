package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.HolderRemovedEvent;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.registertransfer.api.PortfolioMigrationRequest;
import de.makibytes.registerwerk.registertransfer.api.PortfolioMigrationRequestRepository;
import de.makibytes.registerwerk.registertransfer.api.TransferStatus;
import de.makibytes.registerwerk.registertransfer.events.PortfolioMigrationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages portfolio-migration requests — the investor-side, per-holding counterpart to
 * {@link RegisterTransferService} (which is asset-side). Previously an investor leaving the
 * platform had NO path to move their holding to a successor/competitor registrar at all; only
 * the asset's own register/on-chain-control handover existed.
 */
@Service
public class PortfolioMigrationService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioMigrationService.class);
    private static final List<TransferStatus> TERMINAL = List.of(TransferStatus.COMPLETED, TransferStatus.CANCELLED);

    private final PortfolioMigrationRequestRepository repository;
    private final AssetHolderRepository holderRepository;
    private final AssetRepository assetRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final HolderBlockGate holderBlockGate;
    private final FinalityGate finalityGate;

    public PortfolioMigrationService(PortfolioMigrationRequestRepository repository,
                                      AssetHolderRepository holderRepository,
                                      AssetRepository assetRepository,
                                      ObjectMapper objectMapper,
                                      ApplicationEventPublisher eventPublisher,
                                      HolderBlockGate holderBlockGate,
                                      FinalityGate finalityGate) {
        this.repository = repository;
        this.holderRepository = holderRepository;
        this.assetRepository = assetRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.holderBlockGate = holderBlockGate;
        this.finalityGate = finalityGate;
    }

    /**
     * Initiates a migration for one holding. Rejects a second concurrent request for the same
     * holding. Fail-closed §16 eWpG Sperrvermerk check — the earliest possible point to refuse
     * a legally blocked holding, since a portfolio migration moves custody to a successor/
     * competitor registrar entirely, potentially exiting this registry's compliance oversight
     * before a court-ordered freeze would otherwise be noticed.
     */
    @Transactional
    public PortfolioMigrationRequest initiate(UUID holderId, String reason, UUID initiatedBy) {
        AssetHolder holder = holderRepository.findById(holderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset holder " + holderId));
        if (repository.existsByHolderIdAndStatusNotIn(holderId, TERMINAL)) {
            throw new IllegalStateException("A portfolio migration for holder " + holderId + " is already in progress");
        }
        requireNotBlocked(holder);

        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setInvestorEntityId(holder.getInvestorId());
        migration.setAssetId(holder.getAssetId());
        migration.setHolderId(holderId);
        migration.setReason(reason);
        migration.setInitiatedBy(initiatedBy);
        migration.setStatus(TransferStatus.INITIATED);
        PortfolioMigrationRequest saved = repository.save(migration);

        eventPublisher.publishEvent(new PortfolioMigrationEvent(saved.getId(), "INITIATED", initiatedBy, "REGISTRY_ADMIN",
                Map.of("holderId", holderId, "investorEntityId", holder.getInvestorId(), "reason", reason)));
        return saved;
    }

    /** Records the destination (operator must supply this — cannot be inferred). */
    @Transactional
    public PortfolioMigrationRequest setDestination(UUID migrationId, String registrarName, String registrarIdentifier,
                                                     String walletAddress, UUID actorId) {
        PortfolioMigrationRequest migration = load(migrationId);
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
        manifest.put("formatVersion", "1.0");
        manifest.put("standard", "Portfolio migration — investor holding export");
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("investorEntityId", migration.getInvestorEntityId());
        manifest.put("destinationRegistrarName", migration.getDestinationRegistrarName());
        manifest.put("destinationWalletAddress", migration.getDestinationWalletAddress());
        manifest.put("asset", assetSnapshot(asset));
        manifest.put("holding", holderSnapshot(holder));

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
     * {@link HolderBlockGate} as defense-in-depth against a block applied mid-flight, between
     * {@link #initiate} and this (potentially much later) step.
     */
    @Transactional
    public PortfolioMigrationRequest recordOnchainTransfer(UUID migrationId, String txHash, UUID actorId) {
        PortfolioMigrationRequest migration = load(migrationId);
        require(migration.getStatus() == TransferStatus.EXPORTED, "Export must precede the on-chain transfer");
        AssetHolder holder = holderRepository.findById(migration.getHolderId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset holder " + migration.getHolderId()));
        requireNotBlocked(holder);
        migration.setOnchainTxHash(txHash);
        migration.setStatus(TransferStatus.HANDED_OVER);
        migration.setUpdatedAt(Instant.now());
        PortfolioMigrationRequest saved = repository.save(migration);

        eventPublisher.publishEvent(new PortfolioMigrationEvent(migrationId, "HANDED_OVER", actorId, "REGISTRY_ADMIN",
                Map.of("txHash", txHash)));
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

    private Map<String, Object> holderSnapshot(AssetHolder h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("walletAddress", h.getWalletAddress());
        m.put("nominalAmount", h.getNominalAmount() != null ? h.getNominalAmount().toPlainString() : "0");
        m.put("acquisitionDate", h.getAcquisitionDate() != null ? h.getAcquisitionDate().toString() : null);
        return m;
    }

    private PortfolioMigrationRequest load(UUID migrationId) {
        return repository.findById(migrationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown portfolio migration " + migrationId));
    }

    private void requireNotBlocked(AssetHolder holder) {
        if (holderBlockGate.isBlocked(holder.getInvestorId(), holder.getWalletAddress())) {
            throw new de.makibytes.registerwerk.shared.ComplianceGateException(
                    "Holder " + holder.getId() + " is subject to an active §16 eWpG Sperrvermerk "
                    + "(legal block) — portfolio migration refused.");
        }
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
