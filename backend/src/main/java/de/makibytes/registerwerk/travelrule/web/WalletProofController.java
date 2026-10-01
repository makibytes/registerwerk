package de.makibytes.registerwerk.travelrule.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.travelrule.internal.WalletControlProofService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Art. 14(5) TFR wallet-control proofs for registered holder wallets (6-27). A verified proof lets the
 * Travel Rule gate release an outbound transfer to that self-hosted wallet; without one the gate refuses.
 */
@RestController
@RequestMapping("/api/v1/compliance/travel-rule/wallet-proofs")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
@Validated
public class WalletProofController {

    private final WalletControlProofService service;

    WalletProofController(WalletControlProofService service) {
        this.service = service;
    }

    @GetMapping
    public List<WalletControlProofService.ProofView> list(@RequestParam(required = false) UUID legalEntityId) {
        return service.list(legalEntityId);
    }

    /** Issues a personal_sign nonce challenge; the holder signs {@code message} with the wallet. */
    @PostMapping("/challenges")
    public WalletControlProofService.Challenge challenge(@RequestBody @Valid ChallengeRequest request) {
        return service.createChallenge(request.legalEntityId(), request.walletAddress().trim(),
                request.chainConfigId());
    }

    @PostMapping("/{proofId}/signature")
    public WalletControlProofService.ProofView submit(@PathVariable UUID proofId,
                                                      @RequestBody @Valid SignatureRequest request,
                                                      Authentication auth) {
        return service.submitSignature(proofId, request.signature().trim(), SecurityUtils.extractUserId(auth),
                SecurityUtils.primaryRole(auth, "COMPLIANCE_OFFICER"));
    }

    /** Operator attestation for a wallet that cannot sign: step-up plus a second approver, evidence required. */
    @PostMapping("/attestations")
    @RequiresStepUp(requireSecondApprover = true, reason = "TRAVEL_RULE_WALLET_ATTESTATION")
    public WalletControlProofService.ProofView attest(
            @RequestBody @Valid AttestationRequest request, Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return service.attest(request.legalEntityId(), request.walletAddress().trim(), request.evidenceNote(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "COMPLIANCE_OFFICER"), approverId);
    }

    @DeleteMapping("/{proofId}")
    @RequiresStepUp(reason = "TRAVEL_RULE_WALLET_PROOF_REVOKE")
    public ResponseEntity<Void> revoke(@PathVariable UUID proofId, Authentication auth) {
        service.revoke(proofId, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "COMPLIANCE_OFFICER"));
        return ResponseEntity.noContent().build();
    }

    public record ChallengeRequest(@NotNull UUID legalEntityId, @NotBlank @Size(max = 66) String walletAddress,
                                   @NotNull UUID chainConfigId) {}

    public record SignatureRequest(@NotBlank @Size(max = 4096) String signature) {}

    public record AttestationRequest(@NotNull UUID legalEntityId, @NotBlank @Size(max = 66) String walletAddress,
                                     @NotBlank @Size(min = 10, max = 4000) String evidenceNote) {}
}
