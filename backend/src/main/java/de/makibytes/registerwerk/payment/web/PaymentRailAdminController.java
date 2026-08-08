package de.makibytes.registerwerk.payment.web;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.internal.PaymentRailAdminService;
import de.makibytes.registerwerk.payment.web.dto.PaymentRailDtos.ChainAddressRequest;
import de.makibytes.registerwerk.payment.web.dto.PaymentRailDtos.ChainAddressResponse;
import de.makibytes.registerwerk.payment.web.dto.PaymentRailDtos.PaymentRailRequest;
import de.makibytes.registerwerk.payment.web.dto.PaymentRailDtos.PaymentRailResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operator administration of the payment-rail catalog. */
@RestController
@RequestMapping("/api/v1/payment-rails")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class PaymentRailAdminController {

    private final PaymentRailAdminService railService;
    private final PaymentRailRepository railRepository;
    private final PaymentRailChainAddressRepository chainAddressRepository;
    private final ChainConfigRepository chainConfigRepository;

    public PaymentRailAdminController(
            PaymentRailAdminService railService,
            PaymentRailRepository railRepository,
            PaymentRailChainAddressRepository chainAddressRepository,
            ChainConfigRepository chainConfigRepository) {
        this.railService = railService;
        this.railRepository = railRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.chainConfigRepository = chainConfigRepository;
    }

    @GetMapping
    public ResponseEntity<List<PaymentRailResponse>> listRails() {
        List<PaymentRailResponse> rails = railRepository.findAllByOrderByCodeAsc().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(rails);
    }

    @PostMapping
    @RequiresStepUp(reason = "Payment rail creation")
    public ResponseEntity<PaymentRailResponse> createRail(
            @Valid @RequestBody PaymentRailRequest request, Authentication auth) {
        PaymentRail rail = railService.create(
                request.code(), request.displayName(), request.railType(), request.currency(),
                request.decimals(), request.description(), request.issuerName(), request.issuerLei(),
                request.micarAuthorization(), request.emtFlag(), request.whitePaperUrl(), request.redemptionAtPar(),
                toAddressMap(request.chainAddresses()),
                SecurityUtils.extractUserId(auth), primaryRole(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(rail));
    }

    @PutMapping("/{railId}")
    @RequiresStepUp(reason = "Payment rail update", requireSecondApprover = true)
    public ResponseEntity<PaymentRailResponse> updateRail(
            @PathVariable UUID railId,
            @Valid @RequestBody PaymentRailRequest request, Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        PaymentRail rail = railService.update(
                railId, request.displayName(), request.railType(), request.currency(),
                request.decimals(), request.description(), request.issuerName(), request.issuerLei(),
                request.micarAuthorization(), request.emtFlag(), request.whitePaperUrl(), request.redemptionAtPar(),
                toAddressMap(request.chainAddresses()),
                SecurityUtils.extractUserId(auth), primaryRole(auth), approverId);
        return ResponseEntity.ok(toResponse(rail));
    }

    @PostMapping("/{railId}/enable")
    @RequiresStepUp(reason = "Payment rail enablement")
    public ResponseEntity<PaymentRailResponse> enableRail(@PathVariable UUID railId, Authentication auth) {
        return ResponseEntity.ok(toResponse(
                railService.setEnabled(railId, true, SecurityUtils.extractUserId(auth), primaryRole(auth))));
    }

    @PostMapping("/{railId}/disable")
    @RequiresStepUp(reason = "Payment rail deactivation")
    public ResponseEntity<PaymentRailResponse> disableRail(@PathVariable UUID railId, Authentication auth) {
        return ResponseEntity.ok(toResponse(
                railService.setEnabled(railId, false, SecurityUtils.extractUserId(auth), primaryRole(auth))));
    }

    /**
     * Records the operator's own attestation that this rail's MiCAR disclosure fields were
     * checked against a real external source — not a live register cross-check (see
     * {@code PaymentRailAdminService.setMicarVerified}).
     */
    @PostMapping("/{railId}/verify-micar")
    @RequiresStepUp(reason = "Payment rail MiCAR attestation")
    public ResponseEntity<PaymentRailResponse> verifyMicar(@PathVariable UUID railId, Authentication auth) {
        return ResponseEntity.ok(toResponse(
                railService.setMicarVerified(railId, true, SecurityUtils.extractUserId(auth), primaryRole(auth))));
    }

    @PostMapping("/{railId}/unverify-micar")
    @RequiresStepUp(reason = "Payment rail MiCAR attestation cleared")
    public ResponseEntity<PaymentRailResponse> unverifyMicar(@PathVariable UUID railId, Authentication auth) {
        return ResponseEntity.ok(toResponse(
                railService.setMicarVerified(railId, false, SecurityUtils.extractUserId(auth), primaryRole(auth))));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private PaymentRailResponse toResponse(PaymentRail rail) {
        List<ChainAddressResponse> addresses = chainAddressRepository.findByPaymentRailId(rail.getId()).stream()
                .map(address -> new ChainAddressResponse(
                        address.getChainConfigId(),
                        chainConfigRepository.findById(address.getChainConfigId())
                                .map(chain -> chain.getIdentifier()).orElse(null),
                        address.getTokenAddress()))
                .toList();
        return PaymentRailResponse.from(rail, addresses);
    }

    private static Map<UUID, String> toAddressMap(List<ChainAddressRequest> chainAddresses) {
        Map<UUID, String> map = new LinkedHashMap<>();
        if (chainAddresses != null) {
            chainAddresses.forEach(address -> {
                if (map.putIfAbsent(address.chainConfigId(), address.tokenAddress()) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate chainConfigId in payment-rail addresses: " + address.chainConfigId());
                }
            });
        }
        return map;
    }

    private static String primaryRole(Authentication auth) {
        return SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
    }
}
