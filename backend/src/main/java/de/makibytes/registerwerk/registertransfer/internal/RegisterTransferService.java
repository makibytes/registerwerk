package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.registertransfer.api.RegisterTransfer;
import de.makibytes.registerwerk.registertransfer.api.RegisterTransferRepository;
import de.makibytes.registerwerk.registertransfer.api.TransferStatus;
import de.makibytes.registerwerk.registertransfer.events.RegisterTransferEvent;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages §§21/22 eWpG register transfers to a successor operator and produces
 * the §20 eWpRV data-transfer package.
 *
 * <p>The export is a complete, self-describing snapshot of the off-chain
 * register for one asset: the security record, every holder (including the
 * §17(2) single-entry attributes and pseudonymous references), the on-chain
 * deployments, and the full audit trail. The package is hashed (SHA-256 over
 * its canonical JSON) so the successor can verify integrity and the handing-over
 * operator retains proof of exactly what was transferred.
 *
 * <p>The on-chain control handover (two-step registry transfer in the token
 * contracts) is a separate, already-existing mechanism; its transaction hash is
 * recorded here to link the two halves.
 */
@Service
public class RegisterTransferService {

    private static final Logger log = LoggerFactory.getLogger(RegisterTransferService.class);
    private static final int AUDIT_PAGE = 500;

    private final RegisterTransferRepository transferRepository;
    private final AssetRepository assetRepository;
    private final AssetHolderRepository holderRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final AuditApi auditApi;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final FinalityGate finalityGate;

    public RegisterTransferService(
            RegisterTransferRepository transferRepository,
            AssetRepository assetRepository,
            AssetHolderRepository holderRepository,
            AssetDeploymentRepository deploymentRepository,
            AuditApi auditApi,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            FinalityGate finalityGate) {
        this.transferRepository = transferRepository;
        this.assetRepository = assetRepository;
        this.holderRepository = holderRepository;
        this.deploymentRepository = deploymentRepository;
        this.auditApi = auditApi;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.finalityGate = finalityGate;
    }

    /** Initiates a transfer. Rejects a second concurrent transfer for the same asset. */
    @Transactional
    public RegisterTransfer initiate(
            UUID assetId, String successorName, String successorIdentifier,
            String reason, UUID initiatedBy) {
        assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset " + assetId));
        transferRepository.findFirstByAssetIdAndStatusNotInOrderByInitiatedAtDesc(
                assetId, List.of(TransferStatus.COMPLETED, TransferStatus.CANCELLED))
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "A register transfer for asset " + assetId + " is already in progress");
                });

        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(assetId);
        transfer.setSuccessorName(successorName);
        transfer.setSuccessorIdentifier(successorIdentifier);
        transfer.setReason(reason);
        transfer.setInitiatedBy(initiatedBy);
        transfer.setStatus(TransferStatus.INITIATED);
        RegisterTransfer saved = transferRepository.save(transfer);

        eventPublisher.publishEvent(new RegisterTransferEvent(saved.getId(), "INITIATED", initiatedBy, "REGISTRY_ADMIN",
                nullSafeMap("assetId", assetId, "successorName", successorName,
                        "successorIdentifier", successorIdentifier, "reason", reason)));
        return saved;
    }

    /**
     * Builds and records the §20 eWpRV data-transfer package. Returns the
     * canonical JSON bytes for download; the manifest and its hash are persisted.
     */
    @Transactional
    public byte[] export(UUID transferId, UUID actorId) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() == TransferStatus.INITIATED
                        || transfer.getStatus() == TransferStatus.EXPORTED,
                "Only an INITIATED or already-EXPORTED transfer can be (re-)exported");

        Asset asset = assetRepository.findById(transfer.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset " + transfer.getAssetId()));

        // Hard floor: the exported holder snapshot must reflect only FINALIZED register state — a
        // successor operator ingesting this package has no way to later distinguish "confirmed at
        // export time" from "provisional and later reorged." currentLevel is FINALIZED
        // unconditionally (not looked up) because holderSnapshots() reads AssetHolder.nominalAmount,
        // which HolderDataService only ever populates from FINALIZED transfers for chain-derived
        // holders — see RegisterStatementService's identical reasoning for the first gate call site.
        finalityGate.require(GatedOperation.REGISTER_EXTRACT_EXPORT, transfer.getAssetId(), asset.getTokenStandard(),
                FinalityLevel.FINALIZED);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", "1.0");
        manifest.put("standard", "eWpRV §20 register data transfer");
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("asset", assetSnapshot(asset));
        manifest.put("holders", holderSnapshots(transfer.getAssetId()));
        manifest.put("deployments", deploymentSnapshots(transfer.getAssetId()));
        manifest.put("auditTrail", auditSnapshots(transfer.getAssetId()));

        byte[] json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise register export", e);
        }

        transfer.setExportManifest(manifest);
        transfer.setExportHash(sha256Hex(json));
        transfer.setStatus(TransferStatus.EXPORTED);
        transfer.setExportedAt(Instant.now());
        transfer.setUpdatedAt(Instant.now());
        transferRepository.save(transfer);
        log.info("Exported register transfer {} for asset {} ({} bytes)",
                transferId, transfer.getAssetId(), json.length);

        eventPublisher.publishEvent(new RegisterTransferEvent(transferId, "EXPORTED", actorId, "REGISTRY_ADMIN",
                nullSafeMap("assetId", transfer.getAssetId(), "exportHash", transfer.getExportHash(), "bytes", json.length)));
        return json;
    }

    /** Records the on-chain control handover transaction. */
    @Transactional
    public RegisterTransfer recordOnchainHandover(UUID transferId, String txHash, UUID actorId) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() == TransferStatus.EXPORTED,
                "Export must precede the on-chain handover");
        transfer.setOnchainTxHash(txHash);
        transfer.setStatus(TransferStatus.HANDED_OVER);
        transfer.setUpdatedAt(Instant.now());
        RegisterTransfer saved = transferRepository.save(transfer);

        eventPublisher.publishEvent(new RegisterTransferEvent(transferId, "HANDED_OVER", actorId, "REGISTRY_ADMIN",
                Map.of("txHash", txHash)));
        return saved;
    }

    /**
     * Marks the transfer complete once the successor has confirmed receipt, and flips the
     * asset to {@link AssetStatus#TRANSFERRED_OUT} — without this, nothing stops this
     * registrar's own automated jobs ({@code BondMaturityJob}, {@code CouponPaymentJob}) from
     * continuing to auto-raise and dispatch coupon/redemption corporate actions against an
     * asset whose register has already been handed over to a successor operator, risking
     * duplicate/parallel processing between the two registrars.
     */
    @Transactional
    public RegisterTransfer complete(UUID transferId, UUID actorId) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() == TransferStatus.HANDED_OVER,
                "Only a HANDED_OVER transfer can be completed");

        Asset asset = assetRepository.findById(transfer.getAssetId()).orElse(null);
        if (asset != null) {
            // Hard floor, same reasoning as export()'s gate call — completing the transfer is the
            // point of no return (the asset flips to TRANSFERRED_OUT and this registrar's own jobs
            // stop touching it), so the register state it hands off must be FINALIZED, not provisional.
            finalityGate.require(GatedOperation.REGISTER_TRANSFER_COMPLETE, transfer.getAssetId(),
                    asset.getTokenStandard(), FinalityLevel.FINALIZED);
        }

        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setCompletedAt(Instant.now());
        transfer.setUpdatedAt(Instant.now());
        RegisterTransfer saved = transferRepository.save(transfer);

        if (asset != null) {
            asset.setStatus(AssetStatus.TRANSFERRED_OUT);
            assetRepository.save(asset);
        } else {
            log.warn("Asset {} disappeared before it could be marked TRANSFERRED_OUT (transfer={})",
                    transfer.getAssetId(), transferId);
        }

        eventPublisher.publishEvent(new RegisterTransferEvent(transferId, "COMPLETED", actorId, "REGISTRY_ADMIN",
                nullSafeMap("assetId", transfer.getAssetId(), "successorName", transfer.getSuccessorName())));
        return saved;
    }

    @Transactional
    public RegisterTransfer cancel(UUID transferId, String reason, UUID actorId) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() != TransferStatus.COMPLETED,
                "A completed transfer cannot be cancelled");
        String previousStatus = transfer.getStatus() != null ? transfer.getStatus().name() : "";
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setReason(transfer.getReason() + " | cancelled: " + reason);
        transfer.setUpdatedAt(Instant.now());
        RegisterTransfer saved = transferRepository.save(transfer);

        eventPublisher.publishEvent(new RegisterTransferEvent(transferId, "CANCELLED", actorId, "REGISTRY_ADMIN",
                nullSafeMap("assetId", transfer.getAssetId(), "reason", reason, "previousStatus", previousStatus)));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RegisterTransfer> listForAsset(UUID assetId) {
        return transferRepository.findByAssetIdOrderByInitiatedAtDesc(assetId);
    }

    // ── Snapshot builders ──────────────────────────────────────────────────────

    private Map<String, Object> assetSnapshot(Asset asset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", asset.getId());
        m.put("name", asset.getName());
        m.put("isin", asset.getIsin());
        m.put("entryType", asset.getEntryType() != null ? asset.getEntryType().name() : null);
        return m;
    }

    private List<Map<String, Object>> holderSnapshots(UUID assetId) {
        // The §20 eWpRV package hands the successor operator the *current* register to continue
        // maintaining; a removed holder is no longer a register entry (the audit trail, exported
        // separately by this same class, already preserves that history).
        List<AssetHolder> holders = holderRepository.findActiveByAssetId(assetId, Pageable.unpaged()).getContent();
        List<Map<String, Object>> out = new ArrayList<>(holders.size());
        for (AssetHolder h : holders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("investorId", h.getInvestorId());
            m.put("walletAddress", h.getWalletAddress());
            m.put("nominalAmount", h.getNominalAmount() != null ? h.getNominalAmount().toPlainString() : "0");
            m.put("entryType", h.getEntryType() != null ? h.getEntryType().name() : null);
            m.put("holderReference", h.getHolderReference());
            m.put("isConsumer", h.getIsConsumer());
            m.put("thirdPartyRights", h.getThirdPartyRights());
            m.put("disposalRestrictions", h.getDisposalRestrictions());
            m.put("legalCapacityNote", h.getLegalCapacityNote());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> deploymentSnapshots(UUID assetId) {
        List<AssetDeployment> deployments = deploymentRepository.findByAssetId(assetId);
        List<Map<String, Object>> out = new ArrayList<>(deployments.size());
        for (AssetDeployment d : deployments) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("chain", d.getChain() != null ? d.getChain().name() : null);
            m.put("network", d.getNetwork() != null ? d.getNetwork().name() : null);
            m.put("contractAddress", d.getContractAddress());
            m.put("deployedByTx", d.getDeployedByTx());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> auditSnapshots(UUID assetId) {
        List<Map<String, Object>> out = new ArrayList<>();
        int page = 0;
        while (true) {
            Page<AuditEventView> batch = auditApi.findBySubject(
                    "Asset", assetId, PageRequest.of(page, AUDIT_PAGE));
            for (AuditEventView e : batch.getContent()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.id());
                m.put("eventType", e.eventType());
                m.put("actorId", e.actorId());
                m.put("actorRole", e.actorRole());
                m.put("occurredAt", e.occurredAt() != null ? e.occurredAt().toString() : null);
                out.add(m);
            }
            if (batch.isLast()) {
                break;
            }
            page++;
        }
        return out;
    }

    private RegisterTransfer load(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown register transfer " + transferId));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * {@link Map#of} throws NPE on any null value, but several audit-payload fields here
     * (e.g. {@code transfer.getAssetId()} in states reachable during tests/edge cases) can
     * legitimately be null — this drops null entries instead of crashing the audit publish.
     */
    private static Map<String, Object> nullSafeMap(Object... kvPairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            Object value = kvPairs[i + 1];
            if (value != null) {
                map.put((String) kvPairs[i], value);
            }
        }
        return map;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return "0x" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
