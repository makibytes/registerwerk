package de.makibytes.registerwerk.registerstatement.web;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.RegisterHandoverInfoPort;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.kyc.api.JurisdictionRequirementConfig;
import de.makibytes.registerwerk.kyc.api.RegisterDocumentProfile;
import de.makibytes.registerwerk.registerstatement.internal.RegisterStatementService;
import de.makibytes.registerwerk.registerstatement.web.dto.RegisterDocumentMetaResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Self-service register-document downloads for the logged-in investor: a § 19 eWpG
 * Registerauszug for individually entered (Einzeleintragung) holdings, or a
 * jurisdiction-labeled holding confirmation for collectively held (Sammeleintragung /
 * nominee) positions — never the same document; see {@link RegisterDocumentProfile}.
 *
 * <p>Previously the only way to obtain a register statement was for an operator to
 * issue one (e-mail delivery only, {@link RegisterStatementController}); this closes
 * the customer-facing gap, mirroring the existing Depotauszug self-service pattern
 * at {@code GET /api/v1/me/statements}.
 */
@RestController
@RequestMapping("/api/v1/me/register-documents")
@PreAuthorize("isAuthenticated()")
public class MeRegisterDocumentController {

    private final AssetHolderRepository holderRepository;
    private final AssetRepository assetRepository;
    private final JurisdictionRequirementConfig jurisdictionConfig;
    private final RegisterStatementService statementService;
    private final RegisterHandoverInfoPort handoverInfo;

    MeRegisterDocumentController(AssetHolderRepository holderRepository, AssetRepository assetRepository,
                                 JurisdictionRequirementConfig jurisdictionConfig,
                                 RegisterStatementService statementService,
                                 RegisterHandoverInfoPort handoverInfo) {
        this.handoverInfo = handoverInfo;
        this.holderRepository = holderRepository;
        this.assetRepository = assetRepository;
        this.jurisdictionConfig = jurisdictionConfig;
        this.statementService = statementService;
    }

    @GetMapping
    public List<RegisterDocumentMetaResponse> listAvailable(Authentication authentication) {
        UUID investorId = SecurityUtils.extractEntityId(authentication);
        if (investorId == null) {
            return List.of();
        }

        // A removed holder no longer holds the position and has nothing to self-service download.
        List<AssetHolder> holdings = holderRepository.findActiveByInvestorId(investorId);
        Map<UUID, Asset> assetsById = assetRepository
                .findAllById(holdings.stream().map(AssetHolder::getAssetId).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));

        return holdings.stream()
                .map(h -> toMeta(h, assetsById.get(h.getAssetId())))
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/{assetId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID assetId, Authentication authentication) {
        UUID investorId = SecurityUtils.extractEntityId(authentication);
        if (investorId == null) {
            return ResponseEntity.status(403).build();
        }
        AssetHolder holder = holderRepository.findActiveByInvestorIdAndAssetId(investorId, assetId).orElse(null);
        if (holder == null) {
            return ResponseEntity.notFound().build();
        }
        Asset asset = assetRepository.findById(assetId).orElse(null);
        if (asset != null && asset.getStatus() == AssetStatus.TRANSFERRED_OUT) {
            // T3-07: this registrar no longer administers the register.
            throw new InvalidStateTransitionException(handoverInfo.completedHandover(assetId)
                    .map(RegisterHandoverInfoPort.Handover::describe)
                    .orElse("register transferred to a successor registrar"));
        }
        return statementService.renderForDownload(holder.getId())
                .map(pdf -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"registerauszug-" + assetId + "-" + LocalDate.now() + ".pdf\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(pdf))
                // The holder or its asset disappeared between listing and download.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private RegisterDocumentMetaResponse toMeta(AssetHolder holder, Asset asset) {
        if (asset == null) {
            return null;
        }
        boolean individualEntry = holder.getEntryType() == EntryType.INDIVIDUAL;
        RegisterDocumentProfile profile = jurisdictionConfig
                .resolveRegisterDocumentProfile(asset.getJurisdiction(), individualEntry);
        RegisterHandoverInfoPort.Handover handover = asset.getStatus() == AssetStatus.TRANSFERRED_OUT
                ? handoverInfo.completedHandover(asset.getId()).orElse(null) : null;
        return new RegisterDocumentMetaResponse(
                asset.getId(), asset.getIsin(), asset.getName(), asset.getJurisdiction(),
                holder.getEntryType(), profile.docType(), profile.title(), profile.statutory(),
                asset.getStatus() == AssetStatus.TRANSFERRED_OUT
                        ? (handover != null ? handover.successorName() : "successor registrar") : null,
                handover != null ? handover.completedAt() : null);
    }
}
