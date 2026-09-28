package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.SubscriptionOrder;
import de.makibytes.registerwerk.asset.internal.SubscriptionOrderService;
import de.makibytes.registerwerk.asset.web.dto.SubscriptionOrderResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.springframework.security.access.AccessDeniedException;
import de.makibytes.registerwerk.shared.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Primary-market subscription flow — see {@code SubscriptionOrder}'s Javadoc. Submit/cancel/accept
 * are investor-side, scoped to the caller's own entity; list/allocate/reject/confirm-payment/settle/
 * release are issuer/operator-side.
 */
@RestController
@RequestMapping("/api/v1")
public class SubscriptionOrderController {

    private final SubscriptionOrderService service;

    public SubscriptionOrderController(SubscriptionOrderService service) {
        this.service = service;
    }

    // ── Investor-side ────────────────────────────────────────────────────────

    @PostMapping("/assets/{assetId}/orders")
    @PreAuthorize("@assetAccessChecker.canSubscribe(#assetId, authentication)")
    public ResponseEntity<SubscriptionOrderResponse> submit(
            @PathVariable UUID assetId,
            @RequestBody @Valid SubmitOrderRequest request,
            Authentication auth) {
        UUID investorEntityId = SecurityUtils.extractEntityId(auth);
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("INVESTOR");
        SubscriptionOrder order = service.submit(
                assetId, investorEntityId, request.walletAddress(), request.requestedAmount(), actorId, actorRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionOrderResponse.from(order));
    }

    /** The caller's own entity's orders, across every asset. */
    @GetMapping("/me/orders")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SubscriptionOrderResponse>> myOrders(Authentication auth) {
        UUID investorEntityId = SecurityUtils.extractEntityId(auth);
        List<SubscriptionOrderResponse> orders = service.listForInvestor(investorEntityId).stream()
                .map(SubscriptionOrderResponse::from).toList();
        return ResponseEntity.ok(orders);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SubscriptionOrderResponse> cancel(@PathVariable UUID orderId, Authentication auth) {
        requireOwnOrder(orderId, auth);
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("INVESTOR");
        return ResponseEntity.ok(SubscriptionOrderResponse.from(service.cancel(orderId, actorId, actorRole)));
    }

    /** The investor accepts the allocation. {@code /confirm} is the pre-T3-08 path of the same action; it no longer enters the register. */
    @PostMapping({"/orders/{orderId}/accept", "/orders/{orderId}/confirm"})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SubscriptionOrderResponse> accept(@PathVariable UUID orderId, Authentication auth) {
        requireOwnOrder(orderId, auth);
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("INVESTOR");
        return ResponseEntity.ok(SubscriptionOrderResponse.from(service.accept(orderId, actorId, actorRole)));
    }

    /** REGISTRY_ADMIN bypasses the ownership check — everything else must own the order's entity. */
    private void requireOwnOrder(UUID orderId, Authentication auth) {
        if (SecurityUtils.extractRoles(auth).contains("REGISTRY_ADMIN")) return;
        SubscriptionOrder order = service.get(orderId);
        UUID callerEntityId = SecurityUtils.extractEntityId(auth);
        if (callerEntityId == null || !callerEntityId.equals(order.getInvestorEntityId())) {
            throw new AccessDeniedException("Not your order.");
        }
    }

    // ── Issuer/operator-side ────────────────────────────────────────────────

    @GetMapping("/assets/{assetId}/orders")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuer(#assetId, authentication)")
    public ResponseEntity<PageResponse<SubscriptionOrderResponse>> listForAsset(
            @PathVariable UUID assetId, Pageable pageable) {
        Page<SubscriptionOrderResponse> page = service.listForAsset(assetId, pageable).map(SubscriptionOrderResponse::from);
        return ResponseEntity.ok(PageResponse.of(page));
    }

    @PostMapping("/orders/{orderId}/allocate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuerForOrder(#orderId, authentication)")
    public ResponseEntity<SubscriptionOrderResponse> allocate(
            @PathVariable UUID orderId, @RequestBody @Valid AllocateRequest request, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
        SubscriptionOrder order = service.allocate(orderId, request.allocatedAmount(), actorId, actorRole);
        return ResponseEntity.ok(SubscriptionOrderResponse.from(order));
    }

    @PostMapping("/orders/{orderId}/reject")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuerForOrder(#orderId, authentication)")
    public ResponseEntity<SubscriptionOrderResponse> reject(
            @PathVariable UUID orderId, @RequestBody @Valid RejectRequest request, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
        SubscriptionOrder order = service.reject(orderId, request.reason(), actorId, actorRole);
        return ResponseEntity.ok(SubscriptionOrderResponse.from(order));
    }

    /** Cash for an accepted allocation arrived (issuer/operator, step-up). */
    @PostMapping("/orders/{orderId}/confirm-payment")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuerForOrder(#orderId, authentication)")
    @RequiresStepUp(reason = "SUBSCRIPTION_PAYMENT_CONFIRM")
    public ResponseEntity<SubscriptionOrderResponse> confirmPayment(
            @PathVariable UUID orderId, @RequestBody @Valid ConfirmPaymentRequest request, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
        SubscriptionOrder order = service.confirmPayment(orderId, request.paidAmount(), request.paymentReference(),
                request.valueDate(), actorId, actorRole);
        return ResponseEntity.ok(SubscriptionOrderResponse.from(order));
    }

    /** Enters the position on the register (compliance gates re-run; mint on a deployed asset). */
    @PostMapping("/orders/{orderId}/settle")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuerForOrder(#orderId, authentication)")
    @RequiresStepUp(reason = "SUBSCRIPTION_SETTLE")
    public ResponseEntity<SubscriptionOrderResponse> settle(@PathVariable UUID orderId, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
        return ResponseEntity.ok(SubscriptionOrderResponse.from(service.settle(orderId, actorId, actorRole)));
    }

    /** Gives an allocation back (frees its capacity). */
    @PostMapping("/orders/{orderId}/release")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or @assetAccessChecker.canActAsIssuerForOrder(#orderId, authentication)")
    @RequiresStepUp(reason = "SUBSCRIPTION_RELEASE")
    public ResponseEntity<SubscriptionOrderResponse> release(
            @PathVariable UUID orderId, @RequestBody @Valid RejectRequest request, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
        return ResponseEntity.ok(SubscriptionOrderResponse.from(service.release(orderId, request.reason(), actorId, actorRole)));
    }

    public record ConfirmPaymentRequest(@NotNull @Positive BigDecimal paidAmount, @NotBlank String paymentReference,
                                        java.time.LocalDate valueDate) {}
    public record SubmitOrderRequest(@NotBlank String walletAddress, @NotNull @Positive BigDecimal requestedAmount) {}
    public record AllocateRequest(@NotNull @Positive BigDecimal allocatedAmount) {}
    public record RejectRequest(@NotBlank String reason) {}
}
