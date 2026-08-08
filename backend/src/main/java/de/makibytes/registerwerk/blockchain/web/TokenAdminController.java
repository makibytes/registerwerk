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

import de.makibytes.registerwerk.blockchain.api.AssetDeploymentPort;
import de.makibytes.registerwerk.blockchain.api.AssetDeploymentPort.DeploymentRef;
import de.makibytes.registerwerk.blockchain.api.CantonTokenOperations;
import de.makibytes.registerwerk.blockchain.internal.CorrectionCapabilityService;
import de.makibytes.registerwerk.blockchain.internal.CorrectionCapabilityService.CorrectionCapabilitiesResponse;
import de.makibytes.registerwerk.blockchain.internal.TokenAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.blockchain.web.dto.CantonBurnRequest;
import de.makibytes.registerwerk.blockchain.web.dto.CantonForceTransferRequest;
import de.makibytes.registerwerk.blockchain.web.dto.CantonFreezeHoldingRequest;
import de.makibytes.registerwerk.blockchain.web.dto.CantonIssueRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForceBurnRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForceBurnSingleRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForcedApproveRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForcedTransferRequest;
import de.makibytes.registerwerk.blockchain.web.dto.ForcedTransferSingleRequest;
import de.makibytes.registerwerk.blockchain.web.dto.FreezeRequest;
import de.makibytes.registerwerk.blockchain.web.dto.SetSupplyCapRequest;
import de.makibytes.registerwerk.blockchain.web.dto.UnfreezeRequest;
import de.makibytes.registerwerk.blockchain.web.dto.UnwhitelistRequest;
import de.makibytes.registerwerk.blockchain.web.dto.CantonUpdateResponse;
import de.makibytes.registerwerk.blockchain.web.dto.ConfidentialViewerRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import jakarta.validation.Valid;

/**
 * Registry-operator administrative controls for ERC-20, ERC-721, and ERC-1155 token contracts.
 *
 * <p>Base path: {@code /api/v1/assets/{assetId}/deployments/{depId}/admin}
 *
 * <p>All endpoints require {@code REGISTRY_ADMIN}, except the six "forced" admin actions
 * (forced-transfer(-single), forced-approve, force-burn(-single), force-transfer-canton,
 * burn-holding), which a non-admin customer entity may also reach if it holds an active
 * {@code ASSET_TOKEN_ADMIN} grant for the asset (see {@code AssetAccessChecker#canForceAdmin}
 * and {@code AssetTokenAdminGrantController}) — by default nobody has this, not even the
 * asset's own issuer; an operator must explicitly delegate it. Every submission returns a
 * {@link TxSubmissionResponse} with a {@code txId} that can be polled at
 * {@code GET /api/v1/transactions/{txId}}.
 *
 * <p>Step-up / 4-eyes: {@code forced-transfer(-single)}, {@code forced-approve}, and
 * {@code force-burn-single} carry the same {@code requireSecondApprover=true} guard as
 * {@code force-burn} — a wrongful forced-transfer moves assets to an attacker-chosen
 * address and is at least as destructive as a burn, so it must not be reachable by a
 * single actor. {@code set-supply-cap} requires single step-up (not dual-control: it
 * changes a ceiling, not custody of specific holdings).
 *
 * <pre>
 *   POST .../pause                    — suspend all transfers
 *   POST .../unpause                  — resume transfers
 *   POST .../freeze                   — freeze specific address
 *   POST .../unfreeze                 — lift address freeze
 *   POST .../whitelist                — add address to on-chain whitelist
 *   POST .../unwhitelist              — remove address from on-chain whitelist
 *   POST .../forced-transfer          — BaFin/court-ordered transfer (step-up + 4-eyes)
 *   POST .../forced-transfer-single   — ERC-1155: forced transfer of specific token id (step-up + 4-eyes)
 *   POST .../forced-approve           — BaFin/court-ordered approval override (step-up + 4-eyes)
 *   POST .../force-burn               — compulsory cancellation (step-up + 4-eyes)
 *   POST .../force-burn-single        — ERC-1155: forced burn of specific token id (step-up + 4-eyes)
 *   POST .../set-supply-cap           — regulatory issuance ceiling (step-up)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/deployments/{depId}/admin")
@PreAuthorize("hasRole('REGISTRY_ADMIN') and @deploymentAccessChecker.belongsToAsset(#depId, #assetId)")
public class TokenAdminController {

    private static final Logger log = LoggerFactory.getLogger(TokenAdminController.class);

    private final TokenAdminService adminService;
    private final CantonTokenOperations cantonTokenService;
    private final AssetDeploymentPort deploymentPort;
    private final CorrectionCapabilityService correctionCapabilityService;

    public TokenAdminController(
            TokenAdminService adminService,
            CantonTokenOperations cantonTokenService,
            AssetDeploymentPort deploymentPort,
            CorrectionCapabilityService correctionCapabilityService) {
        this.adminService       = adminService;
        this.cantonTokenService = cantonTokenService;
        this.deploymentPort     = deploymentPort;
        this.correctionCapabilityService = correctionCapabilityService;
    }

    /**
     * Returns which corrections are available for this deployment's chain/token standard,
     * and whether each is a reversible in-place cancel or requires booking a compensating
     * transaction — backs the frontend "Correct / Reverse this action" affordance.
     */
    @GetMapping("/corrections")
    public ResponseEntity<CorrectionCapabilitiesResponse> corrections(
            @PathVariable UUID assetId, @PathVariable UUID depId) {
        return ResponseEntity.ok(correctionCapabilityService.getCapabilities(depId));
    }

    @PostMapping("/pause")
    public ResponseEntity<?> pause(
            @PathVariable UUID assetId, @PathVariable UUID depId, Authentication auth) {
        log.info("ADMIN pause deployment={} by actor={}", depId, actorName(auth));
        DeploymentRef dep = loadDeployment(depId);
        if (dep.chain() == Chain.CANTON) {
            return cantonAccepted(cantonTokenService.pauseInstrument(depId, actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")).join());
        }
        return accepted(adminService.pause(depId, actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/unpause")
    public ResponseEntity<?> unpause(
            @PathVariable UUID assetId, @PathVariable UUID depId, Authentication auth) {
        log.info("ADMIN unpause deployment={} by actor={}", depId, actorName(auth));
        DeploymentRef dep = loadDeployment(depId);
        if (dep.chain() == Chain.CANTON) {
            return cantonAccepted(cantonTokenService.unpauseInstrument(depId, actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")).join());
        }
        return accepted(adminService.unpause(depId, actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/freeze")
    public ResponseEntity<TxSubmissionResponse> freeze(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid FreezeRequest request, Authentication auth) {
        log.info("ADMIN freeze address={} on deployment={} by actor={}", request.address(), depId, actorName(auth));
        return accepted(adminService.freezeAddress(depId, request.address(), request.reason(),
                request.legalBasis() != null ? request.legalBasis() : "", actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/unfreeze")
    public ResponseEntity<TxSubmissionResponse> unfreeze(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid UnfreezeRequest request, Authentication auth) {
        log.info("ADMIN unfreeze address={} on deployment={} by actor={}", request.address(), depId, actorName(auth));
        return accepted(adminService.unfreezeAddress(depId, request.address(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/whitelist")
    public ResponseEntity<TxSubmissionResponse> whitelist(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid UnwhitelistRequest request, Authentication auth) {
        log.info("ADMIN whitelist address={} on deployment={} by actor={}", request.address(), depId, actorName(auth));
        return accepted(adminService.whitelist(depId, request.address(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/unwhitelist")
    public ResponseEntity<TxSubmissionResponse> unwhitelist(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid UnwhitelistRequest request, Authentication auth) {
        log.info("ADMIN unwhitelist address={} on deployment={} by actor={}", request.address(), depId, actorName(auth));
        return accepted(adminService.unwhitelist(depId, request.address(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/forced-transfer")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCED_TRANSFER_EWG24")
    public ResponseEntity<TxSubmissionResponse> forcedTransfer(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedTransferRequest request, Authentication auth) {
        log.info("ADMIN forcedTransfer from={} to={} on deployment={} by actor={}", request.from(), request.to(), depId, actorName(auth));
        return accepted(adminService.forcedTransfer(depId, request.from(), request.to(), request.value(), request.legalBasis(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/forced-transfer-single")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCED_TRANSFER_EWG24")
    public ResponseEntity<TxSubmissionResponse> forcedTransferSingle(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedTransferSingleRequest request, Authentication auth) {
        log.info("ADMIN forcedTransferSingle id={} amount={} on deployment={} by actor={}", request.id(), request.amount(), depId, actorName(auth));
        return accepted(adminService.forcedTransferSingle(depId, request.from(), request.to(), request.id(), request.amount(), request.legalBasis(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/forced-approve")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCED_APPROVE_OVERRIDE")
    public ResponseEntity<TxSubmissionResponse> forcedApprove(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForcedApproveRequest request, Authentication auth) {
        log.info("ADMIN forcedApprove owner={} spender={} on deployment={} by actor={}", request.owner(), request.spender(), depId, actorName(auth));
        return accepted(adminService.forcedApprove(depId, request.owner(), request.spender(), request.value(), request.legalBasis(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/force-burn")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCE_BURN_EWG26")
    public ResponseEntity<TxSubmissionResponse> forceBurn(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForceBurnRequest request, Authentication auth) {
        log.info("ADMIN forceBurn from={} value={} on deployment={} by actor={}", request.from(), request.value(), depId, actorName(auth));
        return accepted(adminService.forceBurn(depId, request.from(), request.value(), request.legalBasis(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/force-burn-single")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCE_BURN_EWG26")
    public ResponseEntity<TxSubmissionResponse> forceBurnSingle(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForceBurnSingleRequest request, Authentication auth) {
        log.info("ADMIN forceBurnSingle id={} amount={} on deployment={} by actor={}", request.id(), request.amount(), depId, actorName(auth));
        return accepted(adminService.forceBurnSingle(depId, request.from(), request.id(), request.amount(), request.legalBasis(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    /**
     * Forced burn on a confidential (Zama fhEVM) token — CONF_ERC20/CONF_ERC3643 only. Requires
     * a configured Zama relayer sidecar ({@code registerwerk.zama.relayer-url}) to encrypt
     * {@code value} before submission; see {@code ZamaRelayerClient}'s class-level note on what
     * is and is not verified end-to-end without a live relayer.
     */
    @PostMapping("/force-burn-confidential")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCE_BURN_EWG26")
    public ResponseEntity<TxSubmissionResponse> forceBurnConfidential(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ForceBurnRequest request, Authentication auth) {
        log.info("ADMIN confidentialForceBurn from={} on deployment={} by actor={}", request.from(), depId, actorName(auth));
        return accepted(adminService.confidentialForceBurn(depId, request.from(), request.value(), request.legalBasis(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    /**
     * Grants {@code viewerAddress} decrypt rights on every holder's balance on this confidential
     * token (e.g. adding an auditor, or an issuer's own wallet, after deployment) — see
     * {@code ConfidentialERC20.addViewer}'s doc comment on the additive/non-retroactive ACL model.
     */
    @PostMapping("/confidential-add-viewer")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    public ResponseEntity<TxSubmissionResponse> confidentialAddViewer(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ConfidentialViewerRequest request, Authentication auth) {
        log.info("ADMIN confidentialAddViewer={} on deployment={} by actor={}", request.viewerAddress(), depId, actorName(auth));
        return accepted(adminService.confidentialAddViewer(depId, request.viewerAddress(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    /**
     * Stops granting {@code viewerAddress} decrypt rights on future balance mutations — does NOT
     * retroactively revoke access to already-granted ciphertext handles.
     */
    @PostMapping("/confidential-remove-viewer")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    public ResponseEntity<TxSubmissionResponse> confidentialRemoveViewer(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid ConfidentialViewerRequest request, Authentication auth) {
        log.info("ADMIN confidentialRemoveViewer={} on deployment={} by actor={}", request.viewerAddress(), depId, actorName(auth));
        return accepted(adminService.confidentialRemoveViewer(depId, request.viewerAddress(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    @PostMapping("/set-supply-cap")
    @RequiresStepUp(reason = "SUPPLY_CAP_CHANGE_MICAR46")
    public ResponseEntity<TxSubmissionResponse> setSupplyCap(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid SetSupplyCapRequest request, Authentication auth) {
        log.info("ADMIN setSupplyCap={} on deployment={} by actor={}", request.newCap(), depId, actorName(auth));
        return accepted(adminService.setSupplyCap(depId, request.newCap(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")));
    }

    // ── Canton-only endpoints ─────────────────────────────────────────────────

    /** Issues (mints) Canton tokens to a recipient party. */
    @PostMapping("/issue")
    public ResponseEntity<CantonUpdateResponse> issue(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid CantonIssueRequest request, Authentication auth) {
        log.info("ADMIN Canton issue recipient={} amount={} on deployment={} by actor={}",
                request.recipientPartyId(), request.amount(), depId, actorName(auth));
        String updateId = cantonTokenService.issue(depId, request.recipientPartyId(), request.amount(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")).join();
        return cantonAccepted(updateId);
    }

    /** Freezes a Canton holding (prevents transfers by the holding owner). */
    @PostMapping("/freeze-holding")
    public ResponseEntity<CantonUpdateResponse> freezeHolding(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid CantonFreezeHoldingRequest request, Authentication auth) {
        log.info("ADMIN Canton freeze-holding holdingCid={} on deployment={} by actor={}",
                request.holdingContractId(), depId, actorName(auth));
        String updateId = cantonTokenService.freezeHolding(depId, request.holdingContractId(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")).join();
        return cantonAccepted(updateId);
    }

    /** Unfreezes a Canton holding. */
    @PostMapping("/unfreeze-holding")
    public ResponseEntity<CantonUpdateResponse> unfreezeHolding(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid CantonFreezeHoldingRequest request, Authentication auth) {
        log.info("ADMIN Canton unfreeze-holding holdingCid={} on deployment={} by actor={}",
                request.holdingContractId(), depId, actorName(auth));
        String updateId = cantonTokenService.unfreezeHolding(depId, request.holdingContractId(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")).join();
        return cantonAccepted(updateId);
    }

    /** Forced transfer (issuer-authority, e.g. regulatory recovery). */
    @PostMapping("/force-transfer-canton")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCED_TRANSFER_EWG24")
    public ResponseEntity<CantonUpdateResponse> forceTransferCanton(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid CantonForceTransferRequest request, Authentication auth) {
        log.info("ADMIN Canton force-transfer holdingCid={} to={} amount={} on deployment={} by actor={}",
                request.holdingContractId(), request.toPartyId(), request.amount(), depId, actorName(auth));
        String updateId = cantonTokenService.forceTransfer(
                depId, request.holdingContractId(), request.toPartyId(), request.amount(), request.reason(),
                actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")).join();
        return cantonAccepted(updateId);
    }

    /** Burns Canton tokens from a specific holding. */
    @PostMapping("/burn-holding")
    @PreAuthorize("@deploymentAccessChecker.belongsToAsset(#depId, #assetId) and " +
            "(hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canForceAdmin(#assetId, authentication))")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCE_BURN_EWG26")
    public ResponseEntity<CantonUpdateResponse> burnHolding(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @RequestBody @Valid CantonBurnRequest request, Authentication auth) {
        log.info("ADMIN Canton burn holdingCid={} amount={} on deployment={} by actor={}",
                request.holdingContractId(), request.amount(), depId, actorName(auth));
        String updateId = cantonTokenService.burn(
                depId, request.holdingContractId(), request.amount(), actorId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN")).join();
        return cantonAccepted(updateId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ResponseEntity<TxSubmissionResponse> accepted(UUID txId) {
        return ResponseEntity.accepted().body(new TxSubmissionResponse(txId));
    }

    private static ResponseEntity<CantonUpdateResponse> cantonAccepted(String updateId) {
        return ResponseEntity.accepted().body(new CantonUpdateResponse(updateId));
    }

    private DeploymentRef loadDeployment(UUID depId) {
        return deploymentPort.findById(depId);
    }

    private static String actorName(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }

    private static UUID actorId(Authentication auth) {
        return SecurityUtils.extractUserId(auth);
    }

}
