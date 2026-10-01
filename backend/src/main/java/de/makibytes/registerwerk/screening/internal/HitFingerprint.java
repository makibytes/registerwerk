package de.makibytes.registerwerk.screening.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Identity of a match across screening runs: {@code sha256(provider | listSource | external id |
 * normalised matched value)}. Two runs that return the same provider record for the same subject
 * have the same fingerprint, which is what lets a reviewed false positive be recognised the next night
 * instead of re-blocking the subject (6-18). The score is deliberately not part of it.
 */
final class HitFingerprint {

    private HitFingerprint() {}

    static String of(String provider, String listSource, String externalId, String matchedValue) {
        String canonical = String.join("|", norm(provider), norm(listSource), norm(externalId), norm(matchedValue));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
