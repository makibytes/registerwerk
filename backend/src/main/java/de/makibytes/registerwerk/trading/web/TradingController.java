package de.makibytes.registerwerk.trading.web;

import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import de.makibytes.registerwerk.trading.api.TradingAssetType;
import de.makibytes.registerwerk.trading.api.TradingVenueCode;
import de.makibytes.registerwerk.trading.web.dto.*;
import de.makibytes.registerwerk.shared.api.PageResponse;
import de.makibytes.registerwerk.trading.internal.TradingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trading")
@PreAuthorize("hasAnyRole('TRADER', 'REGISTRY_ADMIN')")
public class TradingController {

    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @GetMapping("/venues")
    public ResponseEntity<List<TradingVenueResponse>> listVenues() {
        return ResponseEntity.ok(tradingService.listVenues());
    }

    @GetMapping("/settings")
    public ResponseEntity<CompanyTraderSettingsResponse> getSettings(Authentication authentication) {
        return ResponseEntity.ok(tradingService.getSettings(extractEntityId(authentication)));
    }

    @PutMapping("/settings")
    public ResponseEntity<CompanyTraderSettingsResponse> updateSettings(
            @RequestBody @Valid UpdateCompanyTraderSettingsRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(tradingService.saveSettings(
                extractEntityId(authentication),
                extractActorId(authentication),
                request));
    }

    @GetMapping("/sellable-holdings")
    public ResponseEntity<List<SellableHoldingResponse>> listSellableHoldings(Authentication authentication) {
        return ResponseEntity.ok(tradingService.listSellableHoldings(extractEntityId(authentication)));
    }

    @GetMapping("/listings")
    public ResponseEntity<List<TradeListingResponse>> listCompanyListings(Authentication authentication) {
        return ResponseEntity.ok(tradingService.listCompanyListings(extractEntityId(authentication)));
    }

    @PostMapping("/listings")
    public ResponseEntity<TradeListingResponse> createListing(
            @RequestBody @Valid CreateTradeListingRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tradingService.createListing(
                extractEntityId(authentication),
                extractActorId(authentication),
                request));
    }

    @DeleteMapping("/listings/{listingId}")
    public ResponseEntity<Void> cancelListing(@PathVariable UUID listingId, Authentication authentication) {
        tradingService.cancelListing(extractEntityId(authentication), extractActorId(authentication), listingId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/marketplace")
    public ResponseEntity<List<TradingVenueOfferResponse>> listMarketplaceOffers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) TradingAssetType assetType,
            @RequestParam(required = false) TokenStandard tokenStandard,
            @RequestParam(required = false) TradingVenueCode venueCode,
            @RequestParam(required = false) PaymentOption paymentOption,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            Authentication authentication) {
        return ResponseEntity.ok(tradingService.listMarketplaceOffers(
                extractEntityId(authentication),
                search,
                assetType,
                tokenStandard,
                venueCode,
                paymentOption,
                minPrice,
                maxPrice));
    }

    @PostMapping("/marketplace/{listingId}/buy")
    public ResponseEntity<TradeExecutionResponse> buy(
            @PathVariable UUID listingId,
            @RequestBody @Valid BuyTradingOfferRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tradingService.buy(
                extractEntityId(authentication),
                extractActorId(authentication),
                listingId,
                request));
    }

    @GetMapping("/history")
    public ResponseEntity<List<TradeExecutionResponse>> listHistory(Authentication authentication) {
        return ResponseEntity.ok(tradingService.listHistory(extractEntityId(authentication)));
    }

    @GetMapping("/history/{executionId}/confirmation")
    public ResponseEntity<byte[]> downloadConfirmation(@PathVariable UUID executionId, Authentication authentication) {
        return tradingService.renderConfirmation(extractEntityId(authentication), executionId)
                .map(pdf -> ResponseEntity.ok()
                        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"trade-confirmation-" + executionId + ".pdf\"")
                        .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                        .body(pdf))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/history/{executionId}/confirmation/iso20022")
    public ResponseEntity<byte[]> downloadIso20022Confirmation(@PathVariable UUID executionId, Authentication authentication) {
        return tradingService.renderIso20022Confirmation(extractEntityId(authentication), executionId)
                .map(xml -> ResponseEntity.ok()
                        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"trade-confirmation-" + executionId + ".xml\"")
                        .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                        .body(xml))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Buyer declares payment on a PENDING trade — does NOT credit the register; moves it to
     *  AWAITING_SELLER_CONFIRMATION and waits for the seller to confirm via {@link #confirmPayment}. */
    @PostMapping("/history/{executionId}/settle")
    public ResponseEntity<TradeExecutionResponse> settle(
            @PathVariable UUID executionId,
            @RequestBody @Valid SettleTradeRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(tradingService.settlePendingTrade(
                extractEntityId(authentication),
                extractActorId(authentication),
                executionId,
                request.paymentReference()));
    }

    /** Seller confirms receipt of the buyer's declared payment — the register is credited here. */
    @PostMapping("/history/{executionId}/confirm-payment")
    public ResponseEntity<TradeExecutionResponse> confirmPayment(
            @PathVariable UUID executionId,
            Authentication authentication) {
        return ResponseEntity.ok(tradingService.confirmPaymentReceived(
                extractEntityId(authentication),
                extractActorId(authentication),
                executionId));
    }

    /** Seller disputes the buyer's declared payment (claims it was never received). */
    @PostMapping("/history/{executionId}/dispute-payment")
    public ResponseEntity<TradeExecutionResponse> disputePayment(
            @PathVariable UUID executionId,
            @RequestBody @Valid DisputeTradeRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(tradingService.disputePayment(
                extractEntityId(authentication),
                extractActorId(authentication),
                executionId,
                request.reason()));
    }

    @PostMapping("/history/{executionId}/cancel")
    public ResponseEntity<TradeExecutionResponse> cancel(
            @PathVariable UUID executionId,
            @RequestBody @Valid de.makibytes.registerwerk.trading.web.dto.CancelTradeRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(tradingService.cancelPendingTrade(
                extractEntityId(authentication),
                extractActorId(authentication),
                executionId,
                request.reason()));
    }

    private UUID extractEntityId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String entityId = jwt.getClaimAsString("entity_id");
            if (entityId == null) {
                entityId = jwt.getClaimAsString("entityId");
            }
            if (entityId != null) {
                return UUID.fromString(entityId);
            }
        }
        throw new IllegalArgumentException("Authenticated entity context is required for trading");
    }

    private UUID extractActorId(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
