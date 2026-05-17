package de.makibytes.registerwerk.wallet.web;

import de.makibytes.registerwerk.wallet.internal.WalletBalanceService;
import de.makibytes.registerwerk.wallet.internal.WalletDefaultService;
import de.makibytes.registerwerk.wallet.internal.WalletService;
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
    private final WalletBalanceService balanceService;

    public WalletController(WalletService walletService, WalletDefaultService defaultService,
                            WalletBalanceService balanceService) {
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
    public ResponseEntity<WalletResponse> importRaw(@RequestBody @Valid WalletImportRawRequest req) {
        OperatorWallet w = walletService.importRaw(
                req.name(), OperatorWallet.WalletType.valueOf(req.type()), req.privateKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(w, defaultService.listAll()));
    }

    @PostMapping(value = "/import-keystore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WalletResponse> importKeystore(
            @RequestParam String name,
            @RequestParam String password,
            @RequestParam("file") MultipartFile file) throws IOException {
        String keystoreJson = new String(file.getBytes(), StandardCharsets.UTF_8);
        OperatorWallet w = walletService.importKeystore(name, keystoreJson, password);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(w, defaultService.listAll()));
    }

    @PostMapping("/{id}/export-keystore")
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

    @PostMapping("/{id}/export-raw")
    public ResponseEntity<String> exportRaw(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            return ResponseEntity.badRequest()
                    .body("Set confirm=true to acknowledge that exporting the raw private key is dangerous.");
        }
        return ResponseEntity.ok(walletService.exportRaw(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WalletResponse> rename(
            @PathVariable UUID id,
            @RequestBody @Valid WalletRenameRequest req) {
        OperatorWallet w = walletService.rename(id, req.name());
        return ResponseEntity.ok(toResponse(w, defaultService.listAll()));
    }

    @DeleteMapping("/{id}")
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
