package de.makibytes.registerwerk.blockchain.web;

import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.blockchain.internal.SolanaTokenAdminService;
import de.makibytes.registerwerk.blockchain.web.dto.SolanaForceBurnRequest;
import de.makibytes.registerwerk.blockchain.web.dto.SolanaForcedTransferRequest;
import de.makibytes.registerwerk.blockchain.web.dto.SolanaTokenAccountRequest;
import de.makibytes.registerwerk.blockchain.web.dto.TxSubmissionResponse;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Registry-operator administrative controls for SPL Token-2022 mints (SPL, SPL_2022,
 * SPL_2022_BOND, SPL_2022_CONFIDENTIAL) — the "" work {@link SolanaTokenAdminService}
 * was built for but never got a controller. Previously the only way to invoke a §24/§26
 * correction on a Solana deployment was direct service-layer code; this closes that gap the
 * same way {@code TokenAdminController} does for EVM.
 *
 * <p>Base path: {@code /api/v1/assets/{assetId}/deployments/{depId}/solana-admin}
 *
 * <p>Step-up / 4-eyes mirrors the EVM surface: forced-transfer and force-burn carry
 * {@code requireSecondApprover=true} (moving or destroying tokens without holder consent);
 * freeze/thaw do not, matching {@code TokenAdminController}'s freeze/unfreeze.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/deployments/{depId}/solana-admin")
@PreAuthorize("hasRole('REGISTRY_ADMIN') and @deploymentAccessChecker.belongsToAsset(#depId, #assetId)")
public class SolanaTokenAdminController {

    private final SolanaTokenAdminService adminService;
    private final AssetDeploymentRepository deploymentRepository;
    private final BlockchainTransactionService txService;
    private final ApplicationEventPublisher eventPublisher;

    public SolanaTokenAdminController(
            SolanaTokenAdminService adminService,
            AssetDeploymentRepository deploymentRepository,
            BlockchainTransactionService txService,
            ApplicationEventPublisher eventPublisher) {
        this.adminService = adminService;
        this.deploymentRepository = deploymentRepository;
        this.txService = txService;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/forced-transfer")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCED_TRANSFER_EWG24")
    public ResponseEntity<TxSubmissionResponse> forcedTransfer(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @Valid @RequestBody SolanaForcedTransferRequest request, Authentication auth) {
        AssetDeployment deployment = loadDeployment(assetId, depId);
        String txHash = adminService.permanentDelegateTransfer(
                depId, request.fromTokenAccount(), request.toTokenAccount(), request.amount(), request.decimals()).join();
        return accepted(recordAndAudit(deployment, txHash, "solanaForcedTransfer", Map.of(
                "fromTokenAccount", request.fromTokenAccount(),
                "toTokenAccount", request.toTokenAccount(),
                "amount", request.amount().toString(),
                "legalBasis", request.legalBasis()
        ), auth));
    }

    @PostMapping("/force-burn")
    @RequiresStepUp(requireSecondApprover = true, reason = "FORCE_BURN_EWG26")
    public ResponseEntity<TxSubmissionResponse> forceBurn(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @Valid @RequestBody SolanaForceBurnRequest request, Authentication auth) {
        AssetDeployment deployment = loadDeployment(assetId, depId);
        String txHash = adminService.permanentDelegateBurn(
                depId, request.tokenAccount(), request.amount(), request.decimals()).join();
        return accepted(recordAndAudit(deployment, txHash, "solanaForceBurn", Map.of(
                "tokenAccount", request.tokenAccount(),
                "amount", request.amount().toString(),
                "legalBasis", request.legalBasis()
        ), auth));
    }

    @PostMapping("/freeze")
    public ResponseEntity<TxSubmissionResponse> freeze(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @Valid @RequestBody SolanaTokenAccountRequest request, Authentication auth) {
        AssetDeployment deployment = loadDeployment(assetId, depId);
        String txHash = adminService.freezeTokenAccount(depId, request.tokenAccount()).join();
        return accepted(recordAndAudit(deployment, txHash, "solanaFreeze",
                Map.of("tokenAccount", request.tokenAccount()), auth));
    }

    @PostMapping("/thaw")
    public ResponseEntity<TxSubmissionResponse> thaw(
            @PathVariable UUID assetId, @PathVariable UUID depId,
            @Valid @RequestBody SolanaTokenAccountRequest request, Authentication auth) {
        AssetDeployment deployment = loadDeployment(assetId, depId);
        String txHash = adminService.thawTokenAccount(depId, request.tokenAccount()).join();
        return accepted(recordAndAudit(deployment, txHash, "solanaThaw",
                Map.of("tokenAccount", request.tokenAccount()), auth));
    }

    /**
     * Single chokepoint every method above funnels through — mirrors
     * {@code TokenAdminService.submitAdmin}: records the transaction for
     * {@code GET /api/v1/transactions/{txId}} polling and publishes {@link TokenAdminActionEvent}
     * to the tamper-evident audit log. {@link SolanaTokenAdminService} itself stays free of
     * audit/tx-tracking wiring so its existing unit tests (which call it directly) keep working.
     */
    private UUID recordAndAudit(AssetDeployment dep, String txHash, String methodName,
                                Map<String, Object> params, Authentication auth) {
        UUID depId = dep.getId();
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN");

        eventPublisher.publishEvent(new TokenAdminActionEvent(depId, methodName, actorId, actorRole, params));

        return txService.record(txHash, methodName, depId, dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
    }

    private AssetDeployment loadDeployment(UUID assetId, UUID depId) {
        return deploymentRepository.findByIdAndAssetId(depId, assetId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", depId));
    }

    private static ResponseEntity<TxSubmissionResponse> accepted(UUID txId) {
        return ResponseEntity.accepted().body(new TxSubmissionResponse(txId));
    }
}
