package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.wallet.WalletDefaultService;
import de.makibytes.registerwerk.domain.wallet.WalletChainDefault;
import de.makibytes.registerwerk.web.dto.wallet.WalletDefaultResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @PutMapping("/{chainId}")
    public ResponseEntity<WalletDefaultResponse> setDefault(
            @PathVariable UUID chainId,
            @RequestBody Map<String, UUID> body) {
        UUID walletId = body.get("walletId");
        if (walletId == null) {
            return ResponseEntity.badRequest().build();
        }
        WalletChainDefault saved = defaultService.setDefault(chainId, walletId);
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
