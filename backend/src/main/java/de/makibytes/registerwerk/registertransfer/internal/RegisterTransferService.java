package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.registertransfer.api.RegisterTransfer;
import de.makibytes.registerwerk.registertransfer.api.RegisterTransferRepository;
import de.makibytes.registerwerk.registertransfer.api.TransferStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public RegisterTransferService(
            RegisterTransferRepository transferRepository,
            AssetRepository assetRepository,
            AssetHolderRepository holderRepository,
            AssetDeploymentRepository deploymentRepository,
            AuditApi auditApi,
            ObjectMapper objectMapper) {
        this.transferRepository = transferRepository;
        this.assetRepository = assetRepository;
        this.holderRepository = holderRepository;
        this.deploymentRepository = deploymentRepository;
        this.auditApi = auditApi;
        this.objectMapper = objectMapper;
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
        return transferRepository.save(transfer);
    }

    /**
     * Builds and records the §20 eWpRV data-transfer package. Returns the
     * canonical JSON bytes for download; the manifest and its hash are persisted.
     */
    @Transactional
    public byte[] export(UUID transferId) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() == TransferStatus.INITIATED
                        || transfer.getStatus() == TransferStatus.EXPORTED,
                "Only an INITIATED or already-EXPORTED transfer can be (re-)exported");

        Asset asset = assetRepository.findById(transfer.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset " + transfer.getAssetId()));

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
        return json;
    }

    /** Records the on-chain control handover transaction. */
    @Transactional
    public RegisterTransfer recordOnchainHandover(UUID transferId, String txHash) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() == TransferStatus.EXPORTED,
                "Export must precede the on-chain handover");
        transfer.setOnchainTxHash(txHash);
        transfer.setStatus(TransferStatus.HANDED_OVER);
        transfer.setUpdatedAt(Instant.now());
        return transferRepository.save(transfer);
    }

    /** Marks the transfer complete once the successor has confirmed receipt. */
    @Transactional
    public RegisterTransfer complete(UUID transferId) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() == TransferStatus.HANDED_OVER,
                "Only a HANDED_OVER transfer can be completed");
        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setCompletedAt(Instant.now());
        transfer.setUpdatedAt(Instant.now());
        return transferRepository.save(transfer);
    }

    @Transactional
    public RegisterTransfer cancel(UUID transferId, String reason) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() != TransferStatus.COMPLETED,
                "A completed transfer cannot be cancelled");
        transfer.setStatus(TransferStatus.CANCELLED);
        transfer.setReason(transfer.getReason() + " | cancelled: " + reason);
        transfer.setUpdatedAt(Instant.now());
        return transferRepository.save(transfer);
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
        List<AssetHolder> holders = holderRepository.findByAssetId(assetId, Pageable.unpaged()).getContent();
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

    private static String sha256Hex(byte[] data) {
        try {
            return "0x" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
