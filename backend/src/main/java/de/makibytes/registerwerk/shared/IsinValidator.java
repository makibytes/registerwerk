package de.makibytes.registerwerk.shared;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ISO 6166 ISIN validation: 2-letter country prefix, 9 alphanumeric characters,
 * and a Luhn check digit computed over the digit expansion (A=10 … Z=35).
 *
 * <p>A syntactically invalid ISIN on an asset would propagate into MiFIR RTS 22
 * transaction reports and corporate-action documents — regulatory filings with a
 * bad instrument identifier are rejected by the NCA, so the registry must refuse
 * the value at the door.
 */
public final class IsinValidator {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    private IsinValidator() {}

    /** @return true if {@code isin} is a structurally valid ISIN with correct check digit. */
    public static boolean isValid(String isin) {
        if (isin == null) {
            return false;
        }
        String normalized = isin.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(normalized).matches()) {
            return false;
        }
        return luhnCheck(expandToDigits(normalized));
    }

    /** Normalizes (trim, upper-case) and validates; throws on invalid input. */
    public static String validateOrThrow(String isin) {
        String normalized = isin == null ? null : isin.trim().toUpperCase(Locale.ROOT);
        if (!isValid(normalized)) {
            throw new IllegalArgumentException(
                    "Invalid ISIN '" + isin + "': expected ISO 6166 format " +
                    "(2-letter country code, 9 alphanumerics, valid check digit).");
        }
        return normalized;
    }

    /** Expands letters to two digits each (A=10 … Z=35), keeps digits as-is. */
    private static String expandToDigits(String isin) {
        StringBuilder sb = new StringBuilder(isin.length() * 2);
        for (char c : isin.toCharArray()) {
            if (Character.isLetter(c)) {
                sb.append(c - 'A' + 10);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Standard Luhn modulus-10: double every second digit from the right. */
    private static boolean luhnCheck(String digits) {
        int sum = 0;
        boolean dbl = true; // start doubling at the second digit from the right
        for (int i = digits.length() - 2; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        int checkDigit = (10 - (sum % 10)) % 10;
        return checkDigit == digits.charAt(digits.length() - 1) - '0';
    }
}
