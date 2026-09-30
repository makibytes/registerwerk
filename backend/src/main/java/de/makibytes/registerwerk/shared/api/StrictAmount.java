package de.makibytes.registerwerk.shared.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * P4B-7: strict deserializers for money/token amount fields ({@code @JsonDeserialize(using = ...)}).
 * A JavaScript client holds numbers as IEEE-754 doubles, so a JSON number above 2^53 has already been
 * rounded before it reaches the API (a 1e21-base-unit mint would silently become a different amount).
 * Amounts are therefore decimal STRINGS. For one release a bare JSON number is still accepted when it
 * is exactly representable (an integer below 2^53, or a decimal of at most 15 significant digits) and a
 * deprecation warning is logged; anything else is a 400 whose message starts with {@link #MESSAGE_PREFIX}.
 */
public final class StrictAmount {

    /** Prefix the global exception handler recognises to surface the reason to the client. */
    public static final String MESSAGE_PREFIX = "Invalid amount: ";

    private static final Logger log = LoggerFactory.getLogger(StrictAmount.class);
    private static final BigInteger MAX_SAFE = BigInteger.TWO.pow(53);
    private static final int MAX_SAFE_DIGITS = 15;
    private static final int MAX_LENGTH = 100;
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

    private StrictAmount() {}

    public static final class BigIntegerAmount extends ValueDeserializer<BigInteger> {
        @Override
        public BigInteger deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_STRING) {
                String text = p.getString().trim();
                if (text.length() > MAX_LENGTH || !INTEGER.matcher(text).matches()) {
                    return ctxt.reportInputMismatch(this, MESSAGE_PREFIX
                            + "expected a decimal integer string (no exponent, no fraction, no separators)");
                }
                return new BigInteger(text);
            }
            if (token == JsonToken.VALUE_NUMBER_INT) {
                BigInteger value = p.getBigIntegerValue();
                if (value.abs().compareTo(MAX_SAFE) >= 0) {
                    return ctxt.reportInputMismatch(this, MESSAGE_PREFIX
                            + "JSON numbers of 2^53 or more lose precision in clients - send the amount as a decimal string");
                }
                log.warn("Deprecated: amount sent as a JSON number; send decimal strings (numbers are rejected in a future release)");
                return value;
            }
            return ctxt.reportInputMismatch(this, MESSAGE_PREFIX
                    + "expected a decimal integer string (a fractional or exponent number is not a valid integer amount)");
        }
    }

    public static final class BigDecimalAmount extends ValueDeserializer<BigDecimal> {
        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_STRING) {
                String text = p.getString().trim();
                if (text.length() > MAX_LENGTH || !DECIMAL.matcher(text).matches()) {
                    return ctxt.reportInputMismatch(this, MESSAGE_PREFIX
                            + "expected a plain decimal string (no exponent, no separators)");
                }
                return new BigDecimal(text);
            }
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                BigDecimal value = p.getDecimalValue();
                if (value.stripTrailingZeros().precision() > MAX_SAFE_DIGITS || value.abs().compareTo(new BigDecimal(MAX_SAFE)) >= 0) {
                    return ctxt.reportInputMismatch(this, MESSAGE_PREFIX
                            + "JSON numbers with more than 15 significant digits lose precision in clients - send the amount as a decimal string");
                }
                log.warn("Deprecated: amount sent as a JSON number; send decimal strings (numbers are rejected in a future release)");
                return value;
            }
            return ctxt.reportInputMismatch(this, MESSAGE_PREFIX + "expected a plain decimal string");
        }
    }
}
