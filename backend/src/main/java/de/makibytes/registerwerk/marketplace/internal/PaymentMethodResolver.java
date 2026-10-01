package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.DappPaymentMethod;
import de.makibytes.registerwerk.marketplace.api.DappPaymentMethodRepository;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.PaymentMethodResponse;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailAttestation;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Resolves a version's declared payment methods to display DTOs, joining rail metadata. */
@Component
public class PaymentMethodResolver {

    private final DappPaymentMethodRepository paymentMethodRepository;
    private final PaymentRailRepository paymentRailRepository;
    private final PaymentRailChainAddressRepository chainAddressRepository;

    PaymentMethodResolver(DappPaymentMethodRepository paymentMethodRepository,
                          PaymentRailRepository paymentRailRepository,
                          PaymentRailChainAddressRepository chainAddressRepository) {
        this.paymentMethodRepository = paymentMethodRepository;
        this.paymentRailRepository = paymentRailRepository;
        this.chainAddressRepository = chainAddressRepository;
    }

    public List<PaymentMethodResponse> resolve(UUID versionId) {
        return paymentMethodRepository.findByVersionId(versionId).stream()
                .map(method -> method.getMethodType() == DappPaymentMethod.MethodType.RAIL
                        ? forRail(method)
                        : PaymentMethodResponse.forCustom(method))
                .toList();
    }

    private PaymentMethodResponse forRail(DappPaymentMethod method) {
        PaymentRail rail = paymentRailRepository.findByCode(method.getRailCode()).orElse(null);
        return PaymentMethodResponse.forRail(method, rail, rail == null ? null
                : PaymentRailAttestation.addressMap(chainAddressRepository.findByPaymentRailId(rail.getId())));
    }
}
