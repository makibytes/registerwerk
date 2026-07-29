package de.makibytes.registerwerk.payment.web;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.web.dto.PaymentRailDtos.ChainAddressResponse;
import de.makibytes.registerwerk.payment.web.dto.PaymentRailDtos.PaymentRailResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only catalog of the enabled payment rails, for publishers picking rails for
 * their manifest and for the marketplace UI. No {@code @PreAuthorize} on purpose: any
 * authenticated user may read it — the global security config keeps all of
 * {@code /api/v1/**} behind JWT authentication.
 */
@RestController
@RequestMapping("/api/v1/payment-rails/catalog")
public class PaymentRailCatalogController {

    private final PaymentRailRepository railRepository;
    private final PaymentRailChainAddressRepository chainAddressRepository;
    private final ChainConfigRepository chainConfigRepository;

    public PaymentRailCatalogController(
            PaymentRailRepository railRepository,
            PaymentRailChainAddressRepository chainAddressRepository,
            ChainConfigRepository chainConfigRepository) {
        this.railRepository = railRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.chainConfigRepository = chainConfigRepository;
    }

    @GetMapping
    public ResponseEntity<List<PaymentRailResponse>> listEnabledRails() {
        List<PaymentRailResponse> rails = railRepository.findByEnabledTrueOrderByCodeAsc().stream()
                .map(rail -> PaymentRailResponse.from(rail,
                        chainAddressRepository.findByPaymentRailId(rail.getId()).stream()
                                .map(address -> new ChainAddressResponse(
                                        address.getChainConfigId(),
                                        chainConfigRepository.findById(address.getChainConfigId())
                                                .map(chain -> chain.getIdentifier()).orElse(null),
                                        address.getTokenAddress()))
                                .toList()))
                .toList();
        return ResponseEntity.ok(rails);
    }
}
