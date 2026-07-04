package de.makibytes.registerwerk.wallet.web;

import de.makibytes.registerwerk.wallet.api.WalletBalancePort;
import de.makibytes.registerwerk.wallet.internal.WalletDefaultService;
import de.makibytes.registerwerk.wallet.internal.WalletService;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.api.WalletChainDefault;
import de.makibytes.registerwerk.wallet.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/wallets")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class WalletController {

    private final WalletService        walletService;
    private final WalletDefaultService defaultService;
    private final WalletBalancePort balanceService;

    public WalletController(WalletService walletService, WalletDefaultService defaultService,
                            WalletBalancePort balanceService) {
        this.walletService  = walletService;
        this.defaultService = defaultService;
        this.balanceService = balanceService;
    }

    @GetMapping
    public ResponseEntity<List<WalletResponse>> list() {
        List<OperatorWallet> wallets = walletService.listAll();
        List<WalletChainDefault> allDefaults = defaultService.listAll();

        List<WalletResponse> response = wallets.stream().map(w -> toResponse(w, allDefaults)).toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> get(@PathVariable UUID id) {
        OperatorWallet w = walletService.getById(id);
        return ResponseEntity.ok(toResponse(w, defaultService.listAll()));
    }

    @GetMapping("/{id}/balances")
    public ResponseEntity<List<WalletBalanceResponse>> getBalances(@PathVariable UUID id) {
        return ResponseEntity.ok(balanceService.getBalances(id));
    }

    @PostMapping("/generate")
    public ResponseEntity<WalletResponse> generate(@RequestBody @Valid WalletGenerateRequest req) {
        OperatorWallet w = walletService.generate(req.name(), OperatorWallet.WalletType.valueOf(req.type()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(w, defaultService.listAll()));
    }

    @PostMapping("/import-raw")
    @RequiresStepUp(reason = "WALLET_IMPORT_RAW")
    public ResponseEntity<WalletResponse> importRaw(@RequestBody @Valid WalletImportRawRequest req) {
        OperatorWallet w = walletService.importRaw(
                req.name(), OperatorWallet.WalletType.valueOf(req.type()), req.privateKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(w, defaultService.listAll()));
    }

    @PostMapping(value = "/import-keystore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresStepUp(reason = "WALLET_IMPORT_KEYSTORE")
    public ResponseEntity<WalletResponse> importKeystore(
            @RequestParam String name,
            @RequestParam String password,
            @RequestParam("file") MultipartFile file) throws IOException {
        String keystoreJson = new String(file.getBytes(), StandardCharsets.UTF_8);
        OperatorWallet w = walletService.importKeystore(name, keystoreJson, password);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(w, defaultService.listAll()));
    }

    /**
     * Produces a password-encrypted keystore of an operator signing key — an offline-bruteable
     * export of key material. Guarded by fresh step-up MFA plus a second approver (4-eyes,
     * GwG §25k / MaRisk AT 4.3.1): a single compromised admin session must not be able to
     * exfiltrate the keys that control every deployed security.
     */
    @PostMapping("/{id}/export-keystore")
    @RequiresStepUp(requireSecondApprover = true, reason = "WALLET_KEYSTORE_EXPORT")
    public ResponseEntity<byte[]> exportKeystore(
            @PathVariable UUID id,
            @RequestBody @Valid WalletExportRequest req) {
        String json = walletService.exportKeystore(id, req.password());
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String filename = "wallet-" + id + ".json";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /**
     * Raw private-key export has been removed (B2 security hardening).
     * Key material must be recovered via the KMS break-glass procedure or offline Shamir shares.
     * See docs/operator/dr/runbook.md for the break-glass procedure.
     */
    @PostMapping("/{id}/export-raw")
    public ResponseEntity<String> exportRaw(@PathVariable UUID id) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
                .body("Raw key export is disabled. Use the KMS break-glass procedure. " +
                      "See docs/operator/dr/runbook.md.");
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WalletResponse> rename(
            @PathVariable UUID id,
            @RequestBody @Valid WalletRenameRequest req) {
        OperatorWallet w = walletService.rename(id, req.name());
        return ResponseEntity.ok(toResponse(w, defaultService.listAll()));
    }

    /**
     * Destroys an operator signing key. Guarded by step-up MFA plus a second approver
     * (4-eyes): losing the key that controls a deployed security must not be a one-click,
     * single-actor operation. On-chain authority should be handed over (transferRegistry)
     * before a still-in-use wallet is deleted.
     */
    @DeleteMapping("/{id}")
    @RequiresStepUp(requireSecondApprover = true, reason = "WALLET_DELETE")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        walletService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static WalletResponse toResponse(OperatorWallet w, List<WalletChainDefault> allDefaults) {
        List<UUID> defaultForChains = allDefaults.stream()
                .filter(d -> d.getWallet().getId().equals(w.getId()))
                .map(WalletChainDefault::getChainConfigId)
                .collect(Collectors.toList());
        return new WalletResponse(
                w.getId(), w.getName(), w.getType().name(), w.getAddress(),
                defaultForChains, w.getCreatedAt(), w.getUpdatedAt());
    }
}
