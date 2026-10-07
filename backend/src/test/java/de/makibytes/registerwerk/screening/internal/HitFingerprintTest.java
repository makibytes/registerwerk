package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort.ScreeningSubjectDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Wave 5b item 6: the carry-forward fingerprint covers the provider record's version and the subject's own data. */
@DisplayName("HitFingerprint")
class HitFingerprintTest {

    private static final UUID ID = UUID.randomUUID();

    private static ScreeningSubjectDto subject(String name, LocalDate dob) {
        return new ScreeningSubjectDto(ID, "NATURAL_PERSON", name, "DE", null, null, dob, "DE");
    }

    private static String fp(String recordVersion, ScreeningSubjectDto s) {
        return HitFingerprint.of("OS", "eu_fsf", "NK-1", "Acme Ltd", recordVersion, HitFingerprint.subjectDigest(s));
    }

    @Test
    @DisplayName("same record version and same subject data -> same fingerprint (carry-forward still works)")
    void stable() {
        assertThat(fp("2026-01-01T00:00:00:abc", subject("Max Mustermann", LocalDate.of(1980, 1, 1))))
                .isEqualTo(fp("2026-01-01T00:00:00:abc", subject("MAX  Mustermann", LocalDate.of(1980, 1, 1))));
    }

    @Test
    @DisplayName("a changed provider record (new last_change / content hash) is a new match")
    void changedListEntryReopens() {
        ScreeningSubjectDto s = subject("Max Mustermann", LocalDate.of(1980, 1, 1));
        assertThat(fp("2026-01-01:abc", s)).isNotEqualTo(fp("2026-03-01:def", s));
        assertThat(fp(null, s)).isNotEqualTo(fp("2026-03-01:def", s));
    }

    @Test
    @DisplayName("changed subject identifying data (name, date of birth, nationality, ids) is a new match")
    void changedSubjectReopens() {
        String base = fp("v1", subject("Max Mustermann", LocalDate.of(1980, 1, 1)));
        assertThat(fp("v1", subject("Maxi Mustermann", LocalDate.of(1980, 1, 1)))).isNotEqualTo(base);
        assertThat(fp("v1", subject("Max Mustermann", LocalDate.of(1981, 1, 1)))).isNotEqualTo(base);
        assertThat(fp("v1", subject("Max Mustermann", null))).isNotEqualTo(base);
        assertThat(fp("v1", new ScreeningSubjectDto(ID, "NATURAL_PERSON", "Max Mustermann", "DE", null, null,
                LocalDate.of(1980, 1, 1), "FR"))).isNotEqualTo(base);
        assertThat(fp("v1", new ScreeningSubjectDto(ID, "LEGAL_ENTITY", "Max Mustermann", "DE", "LEI123", "HRB1",
                null, null))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("the OpenSanctions record version ignores volatile per-query fields but follows the record content")
    void recordVersionFollowsContent() {
        Map<String, Object> a = Map.of("id", "NK-1", "score", 0.91, "last_change", "2026-01-01T00:00:00",
                "properties", Map.of("name", List.of("Acme Ltd", "Acme Limited"), "country", List.of("ru")),
                "topics", List.of("sanction"));
        Map<String, Object> sameRecordOtherScore = Map.of("id", "NK-1", "score", 0.77, "last_change", "2026-01-01T00:00:00",
                "properties", Map.of("country", List.of("ru"), "name", List.of("Acme Limited", "Acme Ltd")),
                "topics", List.of("sanction"));
        Map<String, Object> changedAlias = Map.of("id", "NK-1", "score", 0.91, "last_change", "2026-01-01T00:00:00",
                "properties", Map.of("name", List.of("Acme Ltd", "Acme Limited", "Acme Trading"),
                        "country", List.of("ru")), "topics", List.of("sanction"));
        assertThat(OpenSanctionsAdapter.recordVersionOf(a)).isEqualTo(OpenSanctionsAdapter.recordVersionOf(sameRecordOtherScore));
        assertThat(OpenSanctionsAdapter.recordVersionOf(a)).isNotEqualTo(OpenSanctionsAdapter.recordVersionOf(changedAlias));
        assertThat(OpenSanctionsAdapter.recordVersionOf(Map.of("id", "x"))).isNull();
    }
}
