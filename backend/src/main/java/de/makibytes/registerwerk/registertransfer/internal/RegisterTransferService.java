package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetDocumentRepository;
import de.makibytes.registerwerk.asset.api.AssetDocumentType;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.HandoverBlocker;
import de.makibytes.registerwerk.asset.api.OpenSubscriptionOrdersPort;
import de.makibytes.registerwerk.asset.api.RedemptionReadinessPort;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.shared.RegisterClock;
import org.springframework.cache.CacheManager;
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
import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
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
 * recorded here per deployment to link the two halves.
 *
 * <p><b>Freeze (T3-07).</b> The first export sets the asset to {@link AssetStatus#TRANSFER_PENDING}
 * (previous status kept on the transfer, restored by {@link #cancel}); trading, mint/burn/forced
 * operations, corporate-action processing and register edits are refused until completion. The
 * package carries a {@code registerContentHash} over the register content only - never over the
 * export timestamp - and {@link #complete} recomputes it and refuses (409, naming the changed
 * sections) when the register moved since the export, so the successor never receives a stale
 * package. Holder sync keeps running while pending precisely so that drift is detected.
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
    private final HolderBlockRepository blockRepository;
    private final LegalEntityRepository entityRepository;
    private final AssetBondTermsRepository bondTermsRepository;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final AssetDocumentRepository documentRepository;
    private final RedemptionReadinessPort corporateActionPort;
    private final OpenSubscriptionOrdersPort subscriptionOrdersPort;
    private final OnchainHandoverVerifier handoverVerifier;
    private final RegisterClock registerClock;
    private final CacheManager cacheManager;
    private final ObjectProvider<HandoverBlocker> handoverBlockers;

    public RegisterTransferService(
            RegisterTransferRepository transferRepository,
            AssetRepository assetRepository,
            AssetHolderRepository holderRepository,
            AssetDeploymentRepository deploymentRepository,
            AuditApi auditApi,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            FinalityGate finalityGate,
            HolderBlockRepository blockRepository,
            LegalEntityRepository entityRepository,
            AssetBondTermsRepository bondTermsRepository,
            AssetCouponPaymentRepository couponPaymentRepository,
            AssetDocumentRepository documentRepository,
            RedemptionReadinessPort corporateActionPort,
            OpenSubscriptionOrdersPort subscriptionOrdersPort,
            OnchainHandoverVerifier handoverVerifier,
            RegisterClock registerClock,
            CacheManager cacheManager,
            ObjectProvider<HandoverBlocker> handoverBlockers) {
        this.handoverBlockers = handoverBlockers;
        this.blockRepository = blockRepository;
        this.entityRepository = entityRepository;
        this.bondTermsRepository = bondTermsRepository;
        this.couponPaymentRepository = couponPaymentRepository;
        this.documentRepository = documentRepository;
        this.corporateActionPort = corporateActionPort;
        this.subscriptionOrdersPort = subscriptionOrdersPort;
        this.handoverVerifier = handoverVerifier;
        this.registerClock = registerClock;
        this.cacheManager = cacheManager;
        this.transferRepository = transferRepository;
        this.assetRepository = assetRepository;
        this.holderRepository = holderRepository;
        this.deploymentRepository = deploymentRepository;
        this.auditApi = auditApi;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.finalityGate = finalityGate;
    }

    /** Initiates a transfer without a successor on-chain address (off-chain-only or non-EVM assets). */
    @Transactional
    public RegisterTransfer initiate(
            UUID assetId, String successorName, String successorIdentifier,
            String reason, UUID initiatedBy) {
        return initiate(assetId, successorName, successorIdentifier, reason, initiatedBy, null);
    }

    /**
     * Initiates a transfer. Rejects a second concurrent transfer for the same asset, and an asset
     * already handed over. {@code successorOnchainAddress} is the successor registrar's address the
     * EVM deployments' {@code registry()}/{@code owner()} must equal after the on-chain handover.
     */
    @Transactional
    public RegisterTransfer initiate(
            UUID assetId, String successorName, String successorIdentifier,
            String reason, UUID initiatedBy, String successorOnchainAddress) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset " + assetId));
        require(asset.getStatus() != AssetStatus.TRANSFERRED_OUT,
                "The register of asset " + assetId + " was already transferred out");
        String successorAddress = normaliseAddress(successorOnchainAddress);
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
        transfer.setSuccessorOnchainAddress(successorAddress);
        transfer.setReason(reason);
        transfer.setInitiatedBy(initiatedBy);
        transfer.setStatus(TransferStatus.INITIATED);
        RegisterTransfer saved = transferRepository.save(transfer);

        eventPublisher.publishEvent(new RegisterTransferEvent(saved.getId(), "INITIATED", initiatedBy, "REGISTRY_ADMIN",
                nullSafeMap("assetId", assetId, "successorName", successorName,
                        "successorIdentifier", successorIdentifier, "successorOnchainAddress", successorAddress,
                        "reason", reason)));
        return saved;
    }

    private static String normaliseAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String a = address.trim();
        if (!a.matches("0x[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("successorOnchainAddress must be a 0x-prefixed 20-byte EVM address");
        }
        return a.toLowerCase(java.util.Locale.ROOT);
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
        require(asset.getStatus() != AssetStatus.TRANSFERRED_OUT,
                "The register of asset " + asset.getId() + " was already transferred out");

        // Hard floor: the exported holder snapshot must reflect only FINALIZED register state — a
        // successor operator ingesting this package has no way to later distinguish "confirmed at
        // export time" from "provisional and later reorged." currentLevel is FINALIZED
        // unconditionally (not looked up) because holderSnapshots() reads AssetHolder.nominalAmount,
        // which HolderDataService only ever populates from FINALIZED transfers for chain-derived
        // holders — see RegisterStatementService's identical reasoning for the first gate call site.
        finalityGate.require(GatedOperation.REGISTER_EXTRACT_EXPORT, transfer.getAssetId(), asset.getTokenStandard(),
                FinalityLevel.FINALIZED);

        // T3-07 interim default (who pays a CA whose record date precedes and payment date follows
        // the handover is a parked policy question): settle or cancel such actions before handover.
        LocalDate today = registerClock.today();
        List<RedemptionReadinessPort.OpenAction> blocking = corporateActionPort.openActions(transfer.getAssetId()).stream()
                .filter(a -> a.paymentDate() == null || !a.paymentDate().isBefore(today))
                .toList();
        require(blocking.isEmpty(), "Register handover refused: corporate action(s) "
                + blocking.stream().map(a -> a.actionType() + " " + a.id() + " (payment "
                        + (a.paymentDate() != null ? a.paymentDate() : "n/a") + ")").toList()
                + " are still open - settle or cancel them before the handover");

        // 9A-07 (interim T9-03): pledges, lending collateral and in-flight trades are not part of the §20 package, so the
        // handover is refused while any exists. Re-checked on every (re-)export: a pledge opened after the first
        // export would otherwise slip into the frozen register.
        List<String> encumbrances = new ArrayList<>();
        handoverBlockers.orderedStream()
                .forEach(blocker -> blocker.blocksHandover(transfer.getAssetId()).ifPresent(encumbrances::add));
        require(encumbrances.isEmpty(), "Register handover refused: asset " + transfer.getAssetId()
                + " is encumbered - " + String.join("; ", encumbrances));

        RegisterContent content = computeContent(asset);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", "1.1");
        manifest.put("standard", "eWpRV §20 register data transfer");
        manifest.put("exportedAt", Instant.now().toString());
        // Hash of the register content only (no exportedAt) - recomputed and compared at complete().
        manifest.put("registerContentHash", content.hash());
        manifest.put("registerContentSections", content.sectionHashes());
        manifest.putAll(content.sections());
        manifest.put("deployments", deploymentSnapshots(transfer.getAssetId()));
        manifest.put("auditTrail", auditSnapshots(transfer.getAssetId()));

        byte[] json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise register export", e);
        }

        // Freeze the register from the first export until complete()/cancel().
        if (asset.getStatus() != AssetStatus.TRANSFER_PENDING) {
            transfer.setPreviousAssetStatus(asset.getStatus() != null ? asset.getStatus().name() : null);
            asset.setStatus(AssetStatus.TRANSFER_PENDING);
            assetRepository.save(asset);
            evictAssetCache(asset.getId());
        }

        transfer.setExportManifest(manifest);
        transfer.setExportHash(sha256Hex(json));
        transfer.setRegisterContentHash(content.hash());
        transfer.setStatus(TransferStatus.EXPORTED);
        transfer.setExportedAt(Instant.now());
        transfer.setUpdatedAt(Instant.now());
        transferRepository.save(transfer);
        log.info("Exported register transfer {} for asset {} ({} bytes, contentHash={})",
                transferId, transfer.getAssetId(), json.length, content.hash());

        eventPublisher.publishEvent(new RegisterTransferEvent(transferId, "EXPORTED", actorId, "REGISTRY_ADMIN",
                nullSafeMap("assetId", transfer.getAssetId(), "exportHash", transfer.getExportHash(),
                        "registerContentHash", content.hash(), "bytes", json.length)));
        return json;
    }

    /** Legacy single-transaction form: valid only for an asset with at most one deployment. */
    @Transactional
    public RegisterTransfer recordOnchainHandover(UUID transferId, String txHash, UUID actorId) {
        return recordOnchainHandover(transferId, null, txHash, false, actorId);
    }

    /**
     * Records the on-chain control handover of ONE deployment (T3-07). EVM deployments are verified
     * against the chain: {@code registry()}/{@code owner()} must equal the successor address named at
     * initiation (a mismatch or an unreadable chain refuses - fail closed). Other chains cannot be
     * verified yet and need {@code attested=true} (an explicit operator attestation, on top of the
     * 4-eyes step-up of the endpoint); chain verification for them follows in Phase 4. The transfer
     * becomes HANDED_OVER once every deployment has a record. An asset without deployments has no
     * on-chain half: call with neither deploymentId nor txHash.
     */
    @Transactional
    public RegisterTransfer recordOnchainHandover(UUID transferId, UUID deploymentId, String txHash,
                                                  boolean attested, UUID actorId) {
        RegisterTransfer transfer = load(transferId);
        require(transfer.getStatus() == TransferStatus.EXPORTED
                        || transfer.getStatus() == TransferStatus.HANDED_OVER,
                "Export must precede the on-chain handover");

        List<AssetDeployment> deployments = deploymentRepository.findByAssetId(transfer.getAssetId());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("txHash", txHash);
        if (deployments.isEmpty()) {
            require(deploymentId == null, "Asset has no deployments");
            transfer.setOnchainTxHash(txHash);
            transfer.setStatus(TransferStatus.HANDED_OVER);
        } else {
            AssetDeployment dep;
            if (deploymentId == null && deployments.size() == 1) {
                dep = deployments.get(0);
            } else {
                require(deploymentId != null, "deploymentId is required: the asset has " + deployments.size() + " deployments");
                dep = deployments.stream().filter(d -> deploymentId.equals(d.getId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Deployment " + deploymentId + " does not belong to asset " + transfer.getAssetId()));
            }
            require(txHash != null && !txHash.isBlank(), "txHash is required for a deployment handover");

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("deploymentId", dep.getId().toString());
            record.put("chain", dep.getChain() != null ? dep.getChain().name() : null);
            record.put("contractAddress", dep.getContractAddress());
            record.put("txHash", txHash);
            record.put("recordedAt", Instant.now().toString());
            if (OnchainHandoverVerifier.isEvm(dep.getChain())) {
                require(transfer.getSuccessorOnchainAddress() != null,
                        "This EVM handover cannot be verified: no successorOnchainAddress was set at initiation");
                OnchainHandoverVerifier.Observation obs = handoverVerifier.observeController(dep);
                require(transfer.getSuccessorOnchainAddress().equalsIgnoreCase(obs.observed()),
                        "On-chain handover NOT verified: " + obs.function() + "() of " + dep.getContractAddress()
                                + " is " + obs.observed() + ", expected the successor "
                                + transfer.getSuccessorOnchainAddress());
                record.put("verified", true);
                record.put("method", "RPC_" + obs.function().toUpperCase(java.util.Locale.ROOT));
                record.put("observedController", obs.observed());
            } else {
                require(attested, "Chain " + dep.getChain() + " cannot be verified automatically yet: "
                        + "confirm the handover explicitly (attested=true)");
                record.put("verified", false);
                record.put("method", "OPERATOR_ATTESTED");
                record.put("attestedBy", actorId != null ? actorId.toString() : null);
            }
            List<Map<String, Object>> records = new ArrayList<>(transfer.getOnchainHandovers());
            records.removeIf(r -> dep.getId().toString().equals(String.valueOf(r.get("deploymentId"))));
            records.add(record);
            transfer.setOnchainHandovers(records);
            transfer.setOnchainTxHash(txHash);
            event.put("deploymentId", dep.getId().toString());
            event.put("verified", record.get("verified"));
            event.put("method", record.get("method"));

            Set<String> recorded = new HashSet<>();
            records.forEach(r -> recorded.add(String.valueOf(r.get("deploymentId"))));
            boolean all = deployments.stream().allMatch(d -> recorded.contains(d.getId().toString()));
            transfer.setStatus(all ? TransferStatus.HANDED_OVER : TransferStatus.EXPORTED);
        }
        transfer.setUpdatedAt(Instant.now());
        RegisterTransfer saved = transferRepository.save(transfer);

        eventPublisher.publishEvent(new RegisterTransferEvent(transferId,
                saved.getStatus() == TransferStatus.HANDED_OVER ? "HANDED_OVER" : "DEPLOYMENT_HANDED_OVER",
                actorId, "REGISTRY_ADMIN", event));
        return saved;
    }

    /**
     * Marks the transfer complete once the successor has confirmed receipt, and flips the
     * asset to {@link AssetStatus#TRANSFERRED_OUT} — without this, nothing stops this
     * registrar's own automated jobs ({@code BondMaturityJob}, {@code CouponPaymentJob}) from
     * continuing to auto-raise and dispatch coupon/redemption corporate actions against an
     * asset whose register has already been handed over to a successor operator, risking
     * duplicate/parallel processing between the two registrars.
     *
     * <p>The register content is re-hashed first (T3-07): if anything the successor was given has
     * changed since the export, completion is refused with the changed sections named - re-export.
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
            requireRegisterUnchangedSinceExport(transfer, asset);
        }

        transfer.setStatus(TransferStatus.COMPLETED);
        transfer.setCompletedAt(Instant.now());
        transfer.setUpdatedAt(Instant.now());
        RegisterTransfer saved = transferRepository.save(transfer);

        if (asset != null) {
            asset.setStatus(AssetStatus.TRANSFERRED_OUT);
            assetRepository.save(asset);
            evictAssetCache(asset.getId());
        } else {
            log.warn("Asset {} disappeared before it could be marked TRANSFERRED_OUT (transfer={})",
                    transfer.getAssetId(), transferId);
        }

        eventPublisher.publishEvent(new RegisterTransferEvent(transferId, "COMPLETED", actorId, "REGISTRY_ADMIN",
                nullSafeMap("assetId", transfer.getAssetId(), "successorName", transfer.getSuccessorName(),
                        "registerContentHash", transfer.getRegisterContentHash())));
        return saved;
    }

    @SuppressWarnings("unchecked")
    private void requireRegisterUnchangedSinceExport(RegisterTransfer transfer, Asset asset) {
        if (transfer.getRegisterContentHash() == null) {
            return; // exported before content hashing existed - nothing to compare against
        }
        RegisterContent now = computeContent(asset);
        if (now.hash().equals(transfer.getRegisterContentHash())) {
            return;
        }
        Map<String, Object> stored = transfer.getExportManifest() != null
                && transfer.getExportManifest().get("registerContentSections") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : now.sectionHashes().entrySet()) {
            if (!e.getValue().equals(stored.get(e.getKey()))) {
                changed.add(e.getKey());
            }
        }
        for (String key : stored.keySet()) {
            if (!now.sectionHashes().containsKey(key)) {
                changed.add(key);
            }
        }
        throw new IllegalStateException("Register changed since the export - completion refused; re-export first. "
                + "Changed sections: " + (changed.isEmpty() ? "unknown" : changed));
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

        // Lift the freeze set by export().
        String restoredTo = null;
        Asset asset = assetRepository.findById(transfer.getAssetId()).orElse(null);
        if (asset != null && asset.getStatus() == AssetStatus.TRANSFER_PENDING) {
            AssetStatus restore = AssetStatus.ISSUED;
            if (transfer.getPreviousAssetStatus() != null) {
                try {
                    restore = AssetStatus.valueOf(transfer.getPreviousAssetStatus());
                } catch (IllegalArgumentException ignored) {
                    // unknown legacy value - fall back to ISSUED
                }
            }
            asset.setStatus(restore);
            assetRepository.save(asset);
            evictAssetCache(asset.getId());
            restoredTo = restore.name();
        }

        eventPublisher.publishEvent(new RegisterTransferEvent(transferId, "CANCELLED", actorId, "REGISTRY_ADMIN",
                nullSafeMap("assetId", transfer.getAssetId(), "reason", reason, "previousStatus", previousStatus,
                        "assetStatusRestoredTo", restoredTo)));
        return saved;
    }

    private void evictAssetCache(UUID assetId) {
        try {
            var cache = cacheManager != null ? cacheManager.getCache("assets") : null;
            if (cache != null) {
                cache.evict(assetId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not evict asset cache for {}: {}", assetId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<RegisterTransfer> listForAsset(UUID assetId) {
        return transferRepository.findByAssetIdOrderByInitiatedAtDesc(assetId);
    }

    // ── Snapshot builders ──────────────────────────────────────────────────────

    /** The register content handed to the successor plus its canonical hash (T3-07). */
    record RegisterContent(Map<String, Object> sections, Map<String, String> sectionHashes, String hash) {}

    /** Attributes that change on their own (jobs, status flow) and must not invalidate the package. */
    private RegisterContent computeContent(Asset asset) {
        UUID assetId = asset.getId();
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("asset", assetSnapshot(asset));
        List<AssetHolder> holders = holderRepository.findActiveByAssetId(assetId, Pageable.unpaged()).getContent();
        sections.put("holders", holderSnapshots(holders));
        sections.put("holderBlocks", blockSnapshots(assetId, holders));
        sections.put("bondTerms", bondTermsSnapshot(assetId));
        sections.put("termSheetDocumentHash", termSheetHash(assetId));
        sections.put("openCorporateActions", corporateActionPort.openActions(assetId).stream()
                .sorted(java.util.Comparator.comparing(a -> a.id().toString()))
                .map(a -> {
                    // status deliberately excluded from the hash-relevant projection
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.id());
                    m.put("actionType", a.actionType());
                    m.put("recordDate", a.recordDate());
                    m.put("paymentDate", a.paymentDate());
                    return m;
                }).toList());
        sections.put("openSubscriptionOrders", subscriptionOrdersPort.openOrders(assetId).stream()
                .sorted(java.util.Comparator.comparing(o -> o.id().toString()))
                .map(o -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", o.id());
                    m.put("investorEntityId", o.investorEntityId());
                    m.put("walletAddress", o.walletAddress());
                    m.put("requestedAmount", o.requestedAmount() != null ? o.requestedAmount().toPlainString() : null);
                    m.put("allocatedAmount", o.allocatedAmount() != null ? o.allocatedAmount().toPlainString() : null);
                    m.put("status", o.status());
                    return m;
                }).toList());

        Map<String, String> sectionHashes = new LinkedHashMap<>();
        Map<String, Object> canonicalAll = new java.util.TreeMap<>();
        for (Map.Entry<String, Object> e : sections.entrySet()) {
            Object canon = canonical(e.getValue());
            canonicalAll.put(e.getKey(), canon);
            sectionHashes.put(e.getKey(), sha256Hex(toCanonicalBytes(canon)));
        }
        return new RegisterContent(sections, sectionHashes, sha256Hex(toCanonicalBytes(canonicalAll)));
    }

    /** Recursively sorts map keys and stringifies non-JSON scalars so equal content hashes equal. */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            it.forEach(v -> out.add(canonical(v)));
            return out;
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long) {
            return value;
        }
        if (value instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private byte[] toCanonicalBytes(Object canonical) {
        try {
            return objectMapper.writeValueAsBytes(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalise register content", e);
        }
    }

    private Map<String, Object> assetSnapshot(Asset asset) {
        // status is deliberately absent: export() itself flips it to TRANSFER_PENDING.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", asset.getId());
        m.put("name", asset.getName());
        m.put("isin", asset.getIsin());
        m.put("entryType", asset.getEntryType() != null ? asset.getEntryType().name() : null);
        m.put("tokenStandard", asset.getTokenStandard() != null ? asset.getTokenStandard().name() : null);
        m.put("jurisdiction", asset.getJurisdiction() != null ? asset.getJurisdiction().name() : null);
        m.put("currency", asset.getCurrency());
        m.put("issueSize", asset.getIssueSize() != null ? asset.getIssueSize().toPlainString() : null);
        m.put("denomination", asset.getDenomination() != null ? asset.getDenomination().toPlainString() : null);
        m.put("issueDate", asset.getIssueDate());
        m.put("maturityDate", asset.getMaturityDate());
        LegalEntity issuer = asset.getIssuerId() != null ? entityRepository.findById(asset.getIssuerId()).orElse(null) : null;
        m.put("issuerId", asset.getIssuerId());
        m.put("issuerEntityNumber", issuer != null ? issuer.getEntityNumber() : null);
        m.put("issuerLei", issuer != null ? issuer.getLeiCode() : null);
        return m;
    }

    private List<Map<String, Object>> holderSnapshots(List<AssetHolder> holders) {
        // The §20 eWpRV package hands the successor operator the *current* register to continue
        // maintaining; a removed holder is no longer a register entry (the audit trail, exported
        // separately by this same class, already preserves that history).
        List<Map<String, Object>> out = new ArrayList<>(holders.size());
        for (AssetHolder h : holders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("investorId", h.getInvestorId());
            // Identity: stable internal number and LEI only. Clear names/addresses are a parked
            // policy question (§20 package identity fields) and deliberately omitted.
            LegalEntity investor = h.getInvestorId() != null ? entityRepository.findById(h.getInvestorId()).orElse(null) : null;
            m.put("investorEntityNumber", investor != null ? investor.getEntityNumber() : null);
            m.put("investorLei", investor != null ? investor.getLeiCode() : null);
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
        out.sort(java.util.Comparator.comparing(m -> String.valueOf(m.get("id"))));
        return out;
    }

    /** All ACTIVE HolderBlocks that bind this register: asset-scoped, entity-wide on a holder, or on a holder wallet. */
    private List<Map<String, Object>> blockSnapshots(UUID assetId, List<AssetHolder> holders) {
        Set<UUID> investors = new HashSet<>();
        Set<String> wallets = new HashSet<>();
        for (AssetHolder h : holders) {
            if (h.getInvestorId() != null) investors.add(h.getInvestorId());
            if (h.getWalletAddress() != null) wallets.add(h.getWalletAddress().toLowerCase(java.util.Locale.ROOT));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (HolderBlock b : blockRepository.findByStatusInOrderByCreatedAtDesc(HolderBlock.BLOCKING)) {
            boolean applies = assetId.equals(b.getAssetId())
                    || (b.getEntityId() != null && investors.contains(b.getEntityId()) && b.getAssetId() == null)
                    || (b.getWalletAddress() != null && wallets.contains(b.getWalletAddress().toLowerCase(java.util.Locale.ROOT))
                    && b.getAssetId() == null);
            if (!applies) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("blockType", b.getBlockType() != null ? b.getBlockType().name() : null);
            m.put("assetId", b.getAssetId());
            m.put("entityId", b.getEntityId());
            m.put("walletAddress", b.getWalletAddress());
            m.put("legalBasis", b.getLegalBasis());
            m.put("courtRef", b.getCourtRef());
            m.put("startsAt", b.getStartsAt());
            m.put("expiresAt", b.getExpiresAt());
            m.put("documentId", b.getDocumentId());
            out.add(m);
        }
        out.sort(java.util.Comparator.comparing(m -> String.valueOf(m.get("id"))));
        return out;
    }

    /** Bond terms and the coupon schedule; the time-driven statuses (bond/coupon) are excluded. */
    private Map<String, Object> bondTermsSnapshot(UUID assetId) {
        AssetBondTerms t = bondTermsRepository.findById(assetId).orElse(null);
        if (t == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("faceValue", t.getFaceValue());
        m.put("currencyIso", t.getCurrencyIso());
        m.put("issueDate", t.getIssueDate());
        m.put("maturityDate", t.getMaturityDate());
        m.put("couponRate", t.getCouponRate());
        m.put("referenceRate", t.getReferenceRate());
        m.put("spread", t.getSpread());
        m.put("issuePrice", t.getIssuePrice());
        m.put("dayCount", t.getDayCount());
        m.put("paymentFrequency", t.getPaymentFrequency());
        m.put("callSchedule", t.getCallSchedule());
        m.put("businessDayConvention", t.getBusinessDayConvention());
        m.put("holidayCalendar", t.getHolidayCalendar());
        m.put("recordDateOffsetBd", t.getRecordDateOffsetBd());
        m.put("announcementLeadBd", t.getAnnouncementLeadBd());
        m.put("interestGraceDays", t.getInterestGraceDays());
        m.put("principalGraceDays", t.getPrincipalGraceDays());
        m.put("stubRule", t.getStubRule());
        List<Map<String, Object>> schedule = new ArrayList<>();
        for (AssetCouponPayment p : couponPaymentRepository.findByAssetIdOrderByPeriodNo(assetId)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("periodNo", p.getPeriodNo());
            c.put("scheduledDate", p.getScheduledDate());
            c.put("periodStart", p.getPeriodStart());
            c.put("periodEnd", p.getPeriodEnd());
            c.put("recordDate", p.getRecordDate());
            c.put("announcementDate", p.getAnnouncementDate());
            c.put("amountPerUnit", p.getAmountPerUnit());
            c.put("scheduleVersion", p.getScheduleVersion());
            schedule.add(c);
        }
        m.put("couponSchedule", schedule);
        return m;
    }

    private String termSheetHash(UUID assetId) {
        return documentRepository.findByAssetIdAndDocumentTypeAndDeletedAtIsNull(assetId, AssetDocumentType.TERM_SHEET)
                .stream().findFirst().map(d -> d.getContentHash()).orElse(null);
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
