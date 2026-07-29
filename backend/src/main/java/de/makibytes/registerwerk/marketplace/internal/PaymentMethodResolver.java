package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.DappPaymentMethod;
import de.makibytes.registerwerk.marketplace.api.DappPaymentMethodRepository;
import de.makibytes.registerwerk.marketplace.web.dto.MarketplaceDtos.PaymentMethodResponse;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Resolves a version's declared payment methods to display DTOs, joining rail metadata. */
@Component
public class PaymentMethodResolver {

    private final DappPaymentMethodRepository paymentMethodRepository;
    private final PaymentRailRepository paymentRailRepository;

    PaymentMethodResolver(DappPaymentMethodRepository paymentMethodRepository,
                          PaymentRailRepository paymentRailRepository) {
        this.paymentMethodRepository = paymentMethodRepository;
        this.paymentRailRepository = paymentRailRepository;
    }

    public List<PaymentMethodResponse> resolve(UUID versionId) {
        return paymentMethodRepository.findByVersionId(versionId).stream()
                .map(method -> method.getMethodType() == DappPaymentMethod.MethodType.RAIL
                        ? PaymentMethodResponse.forRail(method,
                                paymentRailRepository.findByCode(method.getRailCode()).orElse(null))
                        : PaymentMethodResponse.forCustom(method))
                .toList();
    }
}
