package de.makibytes.registerwerk.wallet.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.wallet.internal.WalletDefaultService;
import de.makibytes.registerwerk.wallet.api.WalletChainDefault;
import de.makibytes.registerwerk.wallet.web.dto.WalletDefaultResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/wallet-defaults")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class WalletDefaultController {

    private final WalletDefaultService defaultService;

    public WalletDefaultController(WalletDefaultService defaultService) {
        this.defaultService = defaultService;
    }

    @GetMapping
    public ResponseEntity<List<WalletDefaultResponse>> list() {
        List<WalletDefaultResponse> result = defaultService.listAll().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Rewires which wallet signs for a chain — every subsequent mint/burn/transfer on that
     * chain moves to whatever key this points at. Guarded by step-up MFA plus a second
     * approver (4-eyes), matching {@code WalletController}'s export/delete gating: this is
     * at least as consequential as either of those.
     */
    @PutMapping("/{chainId}")
    @RequiresStepUp(requireSecondApprover = true, reason = "WALLET_DEFAULT_CHANGED")
    public ResponseEntity<WalletDefaultResponse> setDefault(
            @PathVariable UUID chainId,
            @RequestBody Map<String, UUID> body,
            Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        UUID walletId = body.get("walletId");
        if (walletId == null) {
            return ResponseEntity.badRequest().build();
        }
        WalletChainDefault saved = defaultService.setDefault(chainId, walletId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId);
        return ResponseEntity.ok(toResponse(saved));
    }

    private WalletDefaultResponse toResponse(WalletChainDefault d) {
        return new WalletDefaultResponse(
                d.getChainConfigId(),
                d.getChainConfig().getIdentifier(),
                d.getChainConfig().getDisplayName(),
                d.getWallet().getId(),
                d.getWallet().getName(),
                d.getWallet().getAddress()
        );
    }
}
