package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningSubjectDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Identity of a match across screening runs: {@code sha256(provider | listSource | external id |
 * normalised matched value | record version | subject digest)}. Two runs that return the same provider
 * record, in the same version, for the same subject data have the same fingerprint, which is what lets a
 * reviewed false positive be recognised the next night instead of re-blocking the subject (6-18).
 * A list entry that changed after the decision (new record version) or a subject whose identifying data
 * changed (name, country, LEI, registration number, date of birth, nationality) is a different match and
 * re-opens the decision (Wave 5b). The score is deliberately not part of it.
 */
final class HitFingerprint {

    private HitFingerprint() {}

    static String of(String provider, String listSource, String externalId, String matchedValue,
                     String recordVersion, String subjectDigest) {
        String canonical = String.join("|", norm(provider), norm(listSource), norm(externalId), norm(matchedValue),
                recordVersion == null ? "" : recordVersion.trim(), subjectDigest == null ? "" : subjectDigest);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Digest of everything the registry itself knows that identifies the screened subject. */
    static String subjectDigest(ScreeningSubjectDto subject) {
        String canonical = String.join("|", norm(subject.subjectType()), norm(subject.name()),
                norm(subject.countryCode()), norm(subject.lei()), norm(subject.registrationNumber()),
                subject.dateOfBirth() == null ? "" : subject.dateOfBirth().toString(), norm(subject.nationality()));
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
