package de.makibytes.registerwerk.blockchain.web;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.makibytes.registerwerk.blockchain.internal.TokenAdminService;
import de.makibytes.registerwerk.blockchain.web.dto.BurnRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForcedApproveRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForcedTransferRequest;
import de.makibytes.registerwerk.blockchain.web.dto.MintRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;

/**
 * Issuer token controls for deployed assets.
 * Base path: {@code /api/v1/assets/{assetId}/deployments/{depId}/issuer}
 *
 * <p>All write operations return a {@link TxSubmissionResponse} with a {@code txId}
 * that can be polled at {@code GET /api/v1/transactions/{txId}}.
 *
 * <p>{@code mint}/{@code burn} are reachable by a customer issuer (not just REGISTRY_ADMIN)
 * via {@code canActAsIssuer} — owning the asset is enough. {@code forced-transfer}/
 * {@code forced-approve} are NOT: owning/issuing the asset is no longer sufficient on its
 * own for either — a wrongful forced-transfer moves an investor's holdings to an
 * attacker-chosen address, so the issuer must additionally hold an explicit, operator-granted
 * {@code ASSET_TOKEN_ADMIN} permission for this asset (see
 * {@code AssetAccessChecker#canForceAdmin} / {@code AssetTokenAdminGrantController}) — by
 * default nobody has this, not even the issuer. They also carry the same
 * {@code requireSecondApprover} 4-eyes guard as the operator-side equivalents in
 * {@code TokenAdminController}: the second approver must be a REGISTRY_ADMIN (enforced by
 * {@code StepUpTokenValidator}), so a customer-side forced-transfer/forced-approve requires
 * both the ASSET_TOKEN_ADMIN grant AND operator sign-off, not just the issuer's own step-up.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/deployments/{depId}/issuer")
@PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
        "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication))")
public class IssuerTokenController {

    private static final Logger log = LoggerFactory.getLogger(IssuerTokenController.class);

    private final TokenAdminService adminService;

    public IssuerTokenController(TokenAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/mint")
    public ResponseEntity<TxSubmissionResponse> mint(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid MintRequest request, Authentication auth) {
        log.info("ISSUER mint to={} amount={} on deployment={} by actor={}",
                request.toAddress(), request.amount(), depId, actorName(auth));
        return accepted(adminService.mint(depId, request.toAddress(), request.amount(), actorId(auth), SecurityUtils.primaryRole(auth, "ISSUER")));
    }

    @PostMapping("/burn")
    public ResponseEntity<TxSubmissionResponse> burn(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid BurnRequest request, Authentication auth) {
        log.info("ISSUER burn from={} amount={} on deployment={} by actor={}",
                request.fromAddress(), request.amount(), depId, actorName(auth));
        return accepted(adminService.regularBurn(depId, request.fromAddress(), request.amount(), actorId(auth), SecurityUtils.primaryRole(auth, "ISSUER")));
    }

    /**
     * Confidential mint (CONF_ERC20/CONF_ERC3643 only) — the ERC-7984 encrypted-amount
     * equivalent of {@link #mint}. Requires a configured Zama relayer sidecar
     * ({@code registerwerk.zama.relayer-url}) to encrypt {@code amount} server-side before
     * submission, since there is no browser/wallet in an issuer-initiated mint.
     */
    @PostMapping("/mint-confidential")
    public ResponseEntity<TxSubmissionResponse> mintConfidential(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid MintRequest request, Authentication auth) {
        log.info("ISSUER confidentialMint to={} on deployment={} by actor={}",
                request.toAddress(), depId, actorName(auth));
        return accepted(adminService.confidentialMint(depId, request.toAddress(), request.amount(),
                actorId(auth), SecurityUtils.primaryRole(auth, "ISSUER")));
    }

    @PostMapping("/forced-transfer")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "ISSUER_FORCED_TRANSFER_EWG24")
    public ResponseEntity<TxSubmissionResponse> forcedTransfer(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedTransferRequest request, Authentication auth) {
        log.info("ISSUER forcedTransfer from={} to={} on deployment={} by actor={}",
                request.from(), request.to(), depId, actorName(auth));
        return accepted(adminService.forcedTransfer(depId, request.from(), request.to(), request.value(), request.legalBasis(),
                actorId(auth), SecurityUtils.primaryRole(auth, "ISSUER")));
    }

    @PostMapping("/forced-approve")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "ISSUER_FORCED_APPROVE_OVERRIDE")
    public ResponseEntity<TxSubmissionResponse> forcedApprove(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedApproveRequest request, Authentication auth) {
        log.info("ISSUER forcedApprove owner={} spender={} on deployment={} by actor={}",
                request.owner(), request.spender(), depId, actorName(auth));
        return accepted(adminService.forcedApprove(depId, request.owner(), request.spender(), request.value(), request.legalBasis(),
                actorId(auth), SecurityUtils.primaryRole(auth, "ISSUER")));
    }

    private static ResponseEntity<TxSubmissionResponse> accepted(UUID txId) {
        return ResponseEntity.accepted().body(new TxSubmissionResponse(txId));
    }

    private static String actorName(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }

    private static UUID actorId(Authentication auth) {
        return SecurityUtils.extractUserId(auth);
    }

}
