package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.registertransfer.api.InspectionLegalBasis;
import de.makibytes.registerwerk.registertransfer.api.InspectionStatus;
import de.makibytes.registerwerk.registertransfer.api.RegisterInspectionRequest;
import de.makibytes.registerwerk.registertransfer.api.RegisterInspectionRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Handles §10 eWpG register inspection requests.
 *
 * <p>Decision logic follows §10(2) eWpRV: a Berechtigter (issuer, holder, or —
 * in single entry — a beneficiary in whose favour a right is recorded) always
 * has a legitimate interest and is approved automatically. Any other applicant
 * (LEGITIMATE_INTEREST) is left REQUESTED for an operator to review, since the
 * operator must weigh the asserted interest.
 *
 * <p>Fulfilling an approved request renders the disclosed register extract and
 * records its SHA-256 hash, so the operator retains tamper-evident proof of
 * exactly what was disclosed and when.
 */
@Service
public class RegisterInspectionService {

    private static final Logger log = LoggerFactory.getLogger(RegisterInspectionService.class);

    private final RegisterInspectionRequestRepository requestRepository;
    private final AssetRepository assetRepository;
    private final AssetHolderRepository holderRepository;
    private final RegisterExtractRenderer extractRenderer;

    public RegisterInspectionService(
            RegisterInspectionRequestRepository requestRepository,
            AssetRepository assetRepository,
            AssetHolderRepository holderRepository,
            RegisterExtractRenderer extractRenderer) {
        this.requestRepository = requestRepository;
        this.assetRepository = assetRepository;
        this.holderRepository = holderRepository;
        this.extractRenderer = extractRenderer;
    }

    /**
     * Submits a new inspection request. Berechtigte are auto-approved; other
     * applicants remain pending operator review.
     */
    @Transactional
    public RegisterInspectionRequest submit(
            UUID assetId, UUID requesterEntityId, String requesterName, String requesterEmail,
            InspectionLegalBasis legalBasis, String statedInterest) {
        assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset " + assetId));

        RegisterInspectionRequest request = new RegisterInspectionRequest();
        request.setAssetId(assetId);
        request.setRequesterEntityId(requesterEntityId);
        request.setRequesterName(requesterName);
        request.setRequesterEmail(requesterEmail);
        request.setLegalBasis(legalBasis);
        request.setStatedInterest(statedInterest);

        if (isAlwaysEntitled(legalBasis)) {
            request.setStatus(InspectionStatus.APPROVED);
            request.setDecisionReason("Berechtigter — legitimate interest presumed (§10(2) eWpRV)");
            request.setDecidedAt(Instant.now());
        } else {
            request.setStatus(InspectionStatus.REQUESTED);
        }
        return requestRepository.save(request);
    }

    /** Operator approves a pending LEGITIMATE_INTEREST request. */
    @Transactional
    public RegisterInspectionRequest approve(UUID requestId, UUID operatorEntityId, String reason) {
        RegisterInspectionRequest request = load(requestId);
        require(request.getStatus() == InspectionStatus.REQUESTED,
                "Only a REQUESTED inspection can be approved");
        request.setStatus(InspectionStatus.APPROVED);
        request.setDecisionReason(reason);
        request.setDecidedBy(operatorEntityId);
        request.setDecidedAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        return requestRepository.save(request);
    }

    /** Operator rejects a pending request. */
    @Transactional
    public RegisterInspectionRequest reject(UUID requestId, UUID operatorEntityId, String reason) {
        RegisterInspectionRequest request = load(requestId);
        require(request.getStatus() == InspectionStatus.REQUESTED,
                "Only a REQUESTED inspection can be rejected");
        request.setStatus(InspectionStatus.REJECTED);
        request.setDecisionReason(reason);
        request.setDecidedBy(operatorEntityId);
        request.setDecidedAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        return requestRepository.save(request);
    }

    /**
     * Fulfils an approved request by rendering the register extract. Returns the
     * rendered PDF bytes and records the disclosure (hash + timestamp).
     */
    @Transactional
    public byte[] fulfil(UUID requestId) {
        RegisterInspectionRequest request = load(requestId);
        require(request.getStatus() == InspectionStatus.APPROVED,
                "Only an APPROVED inspection can be fulfilled");

        Asset asset = assetRepository.findById(request.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset " + request.getAssetId()));
        List<AssetHolder> holders = holderRepository
                .findByAssetId(request.getAssetId(), Pageable.unpaged()).getContent();

        byte[] pdf = extractRenderer.render(asset, holders, request.getLegalBasis(),
                request.getRequesterName());

        request.setContentHash(sha256Hex(pdf));
        request.setStatus(InspectionStatus.FULFILLED);
        request.setFulfilledAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        requestRepository.save(request);
        log.info("Fulfilled inspection {} for asset {}", requestId, request.getAssetId());
        return pdf;
    }

    @Transactional(readOnly = true)
    public Page<RegisterInspectionRequest> listForAsset(UUID assetId, Pageable pageable) {
        return requestRepository.findByAssetIdOrderByCreatedAtDesc(assetId, pageable);
    }

    private boolean isAlwaysEntitled(InspectionLegalBasis basis) {
        return basis == InspectionLegalBasis.ISSUER
                || basis == InspectionLegalBasis.HOLDER
                || basis == InspectionLegalBasis.BENEFICIARY;
    }

    private RegisterInspectionRequest load(UUID requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown inspection request " + requestId));
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
