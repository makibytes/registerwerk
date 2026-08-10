package de.makibytes.registerwerk.lending.web;

import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.internal.LendingMarketService;
import de.makibytes.registerwerk.lending.internal.LendingMarketService.LendingQuote;
import de.makibytes.registerwerk.lending.internal.LendingMarketService.MarketView;
import de.makibytes.registerwerk.lending.web.dto.LendingMarketResponse;
import de.makibytes.registerwerk.lending.web.dto.LendingQuoteResponse;
import de.makibytes.registerwerk.lending.web.dto.RegisterLendingMarketRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * Market catalog + borrow-terms quotes for the {@code lending} read-model. See
 * {@code contracts/src/lending/EwpgRepoMarket.sol} for the on-chain mechanics this fronts.
 */
@RestController
@RequestMapping("/api/v1/lending/markets")
public class LendingMarketController {

    private final LendingMarketService marketService;

    public LendingMarketController(LendingMarketService marketService) {
        this.marketService = marketService;
    }

    @GetMapping
    public ResponseEntity<List<LendingMarketResponse>> listMarkets(
            @RequestParam(required = false) LendingMarketStatus status) {
        return ResponseEntity.ok(marketService.listMarkets(status).stream().map(this::toResponse).toList());
    }

    @GetMapping("/{marketId}")
    public ResponseEntity<LendingMarketResponse> getMarket(@PathVariable UUID marketId) {
        return ResponseEntity.ok(toResponse(marketService.getMarket(marketId)));
    }

    @GetMapping("/{marketId}/quote")
    public ResponseEntity<LendingQuoteResponse> quote(
            @PathVariable UUID marketId, @RequestParam BigInteger collateralAmount) {
        LendingQuote quote = marketService.quote(marketId, collateralAmount);
        return ResponseEntity.ok(new LendingQuoteResponse(
                quote.marketId(), quote.collateralAmount(), quote.pricePerUnit(), quote.priceUpdatedAt(),
                quote.maxBorrowAmount(), quote.maxLtvBps(), quote.lltvBps(), quote.utilizationWad(),
                quote.borrowRateWad(), quote.availableLiquidity(), quote.oracleReliable()));
    }

    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<LendingMarketResponse> registerMarket(
            @RequestBody @Valid RegisterLendingMarketRequest request, Authentication authentication) {
        MarketView view = marketService.registerMarket(
                request.chainConfigId(), request.marketAddress(), request.vaultAddress(),
                request.collateralAssetId(), request.loanRailCode(),
                extractActorId(authentication), "REGISTRY_ADMIN");
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(view));
    }

    private UUID extractActorId(Authentication authentication) {
        if (authentication == null) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private LendingMarketResponse toResponse(MarketView view) {
        var market = view.market();
        return new LendingMarketResponse(
                market.getId(), market.getChainConfigId(), market.getMarketAddress(), market.getVaultAddress(),
                market.getCollateralAssetId(), view.collateralAssetName(), view.collateralIsin(),
                market.getCollateralTokenAddress(), market.getLoanTokenAddress(), market.getLoanRailCode(),
                market.getLoanTokenDecimals(), market.getMaxLtvBps(), market.getLltvBps(),
                market.getLiquidationBonusBps(), market.getBaseRateWad(), market.getSlopeWad(),
                market.getMaxPriceAgeSeconds(), market.getLiquidationGracePeriodSeconds(),
                market.getPriceOracleAddress(), view.effectiveStatus(), view.jurisdiction(), view.micarApplicable(),
                view.defiInteropModel(), market.getCreatedAt());
    }
}
