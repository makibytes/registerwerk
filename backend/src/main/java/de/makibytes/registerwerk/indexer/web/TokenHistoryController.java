package de.makibytes.registerwerk.indexer.web;

import de.makibytes.registerwerk.indexer.internal.TokenHistoryService;
import de.makibytes.registerwerk.indexer.internal.TokenHistoryService.TokenTransferSummary;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.shared.api.PageResponse;
import de.makibytes.registerwerk.indexer.web.dto.TokenTransferResponse;
import de.makibytes.registerwerk.indexer.web.TokenTransferMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller exposing token transfer history for assets and their deployments.
 */
@RestController
@RequestMapping("/api/v1")
public class TokenHistoryController {

    private static final Logger log = LoggerFactory.getLogger(TokenHistoryController.class);

    private final TokenHistoryService tokenHistoryService;
    private final TokenTransferMapper tokenTransferMapper;

    public TokenHistoryController(
            TokenHistoryService tokenHistoryService,
            TokenTransferMapper tokenTransferMapper) {
        this.tokenHistoryService = tokenHistoryService;
        this.tokenTransferMapper = tokenTransferMapper;
    }

    /**
     * Returns a paginated list of all token transfers linked to the given asset,
     * across all its deployments.
     */
    @GetMapping("/assets/{assetId}/history")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#deploymentId, #assetId) and "
            + "(hasRole('REGISTRY_ADMIN') or hasRole('AUDIT') "
            + "or @assetAccessChecker.canRead(#assetId, authentication))")
    public ResponseEntity<PageResponse<TokenTransferResponse>> getAssetHistory(
            @PathVariable UUID assetId,
            Pageable pageable,
            Authentication authentication) {
        Page<TokenTransfer> page = tokenHistoryService.getTransfersByAsset(assetId, pageable);
        Page<TokenTransferResponse> mapped = page.map(tokenTransferMapper::toResponseWithChain);
        return ResponseEntity.ok(PageResponse.of(mapped));
    }

    /**
     * Returns a paginated list of token transfers linked to a specific deployment of an asset.
     */
    @GetMapping("/assets/{assetId}/deployments/{deploymentId}/history")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#deploymentId, #assetId) and "
            + "(hasRole('REGISTRY_ADMIN') or hasRole('AUDIT') "
            + "or @assetAccessChecker.canRead(#assetId, authentication))")
    public ResponseEntity<PageResponse<TokenTransferResponse>> getDeploymentHistory(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            Pageable pageable,
            Authentication authentication) {
        Page<TokenTransfer> page = tokenHistoryService.getTransfersByDeployment(deploymentId, pageable);
        Page<TokenTransferResponse> mapped = page.map(tokenTransferMapper::toResponseWithChain);
        return ResponseEntity.ok(PageResponse.of(mapped));
    }

    /**
     * Returns aggregate statistics (mint/burn/transfer counts, holder count) for a deployment.
     */
    @GetMapping("/assets/{assetId}/deployments/{deploymentId}/history/summary")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT') "
            + "or @assetAccessChecker.canRead(#assetId, authentication)")
    public ResponseEntity<TokenTransferSummary> getSummary(
            @PathVariable UUID assetId,
            @PathVariable UUID deploymentId,
            Authentication authentication) {
        TokenTransferSummary summary = tokenHistoryService.getSummary(deploymentId);
        return ResponseEntity.ok(summary);
    }
}
