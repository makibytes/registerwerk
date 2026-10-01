package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import de.makibytes.registerwerk.trading.api.PaymentOption;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Settlement-currency rules for a listing (Phase 5, 5A-02 / parked T5-03). Fiat options
 * (SEPA / CBMT / Pontes) accept a currency from {@code registerwerk.trading.fiat-currencies}
 * (default EUR); STABLECOIN needs an ENABLED stablecoin {@code payment_rail} and takes its
 * currency; NATIVE_CHAIN_CURRENCY has no resolvable native symbol here and is refused (interim).
 * Price convention: per unit.
 */
@Component
class TradeCurrencyPolicy {

    private final PaymentRailRepository railRepository;
    private final TradingProperties properties;

    TradeCurrencyPolicy(PaymentRailRepository railRepository, TradingProperties properties) {
        this.railRepository = railRepository;
        this.properties = properties;
    }

    /** {@code paymentRailCode} is null unless STABLECOIN is offered. */
    record Resolved(String currency, String paymentRailCode) {
    }

    Resolved resolve(Asset asset, String requestedCurrency, String requestedRailCode, Set<PaymentOption> options) {
        if (options.contains(PaymentOption.NATIVE_CHAIN_CURRENCY)) {
            throw new IllegalArgumentException("Native chain currency is not supported as a settlement currency "
                    + "yet - offer a fiat option or an enabled stablecoin rail instead.");
        }
        String currency = normalise(requestedCurrency);
        String railCode = requestedRailCode == null || requestedRailCode.isBlank() ? null : requestedRailCode.trim();
        boolean stable = options.contains(PaymentOption.STABLECOIN);
        boolean fiat = options.stream().anyMatch(o -> o != PaymentOption.STABLECOIN);

        if (stable) {
            if (railCode == null) {
                throw new IllegalArgumentException("A stablecoin listing must name the payment rail (paymentRailCode)");
            }
            PaymentRail rail = railRepository.findByCode(railCode)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown payment rail: " + railCode));
            if (!rail.isEnabled() || rail.getRailType() != PaymentRailType.STABLECOIN) {
                throw new IllegalArgumentException("Payment rail " + railCode + " is not an enabled stablecoin rail");
            }
            String railCurrency = normalise(rail.getCurrency());
            if (currency != null && !currency.equals(railCurrency)) {
                throw new IllegalArgumentException("Currency " + currency + " does not match payment rail "
                        + railCode + " (" + railCurrency + ")");
            }
            currency = railCurrency;
        } else if (railCode != null) {
            throw new IllegalArgumentException("paymentRailCode is only valid together with the STABLECOIN payment option");
        }

        if (currency == null && asset != null) {
            currency = normalise(asset.getCurrency());
        }
        if (currency == null) {
            throw new IllegalArgumentException("Currency is required: the asset records no denomination currency");
        }
        if (fiat) {
            if (!properties.getFiatCurrencies().stream().map(TradeCurrencyPolicy::normalise).toList().contains(currency)) {
                throw new IllegalArgumentException("Currency " + currency + " is not accepted for the chosen payment "
                        + "options (allowed: " + String.join(", ", properties.getFiatCurrencies()) + ")");
            }
            Money.minorUnits(currency); // valid ISO 4217
        }
        return new Resolved(currency, stable ? railCode : null);
    }

    /** Rounding scale of the selected payment option for a stored listing currency / rail. */
    int scaleFor(PaymentOption option, String currency, String railCode) {
        if (option == PaymentOption.STABLECOIN && railCode != null) {
            Integer decimals = railRepository.findByCode(railCode).map(PaymentRail::getDecimals).orElse(null);
            return Money.stablecoinScale(decimals);
        }
        return Money.minorUnits(currency);
    }

    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
