package de.makibytes.registerwerk.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CsvWriter RFC 4180 quoting and formula-injection guard (6-35)")
class CsvWriterTest {

    @Test
    @DisplayName("a formula cell is neutralised with a leading apostrophe (and quoted when it contains a comma/quote)")
    void formulaCellsAreNeutralised() {
        assertThat(CsvWriter.escape("=cmd|' /C calc'!A0")).isEqualTo("'=cmd|' /C calc'!A0");
        assertThat(CsvWriter.escape("=HYPERLINK(\"http://evil\",\"x\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"");
        assertThat(CsvWriter.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvWriter.escape("+49 170 1234567")).isEqualTo("'+49 170 1234567");
        assertThat(CsvWriter.escape("-1+1|calc")).isEqualTo("'-1+1|calc");
        assertThat(CsvWriter.escape("\tTAB")).isEqualTo("'\tTAB");
    }

    @Test
    @DisplayName("pure numbers, dates and ordinary text are untouched")
    void numbersAndTextUnchanged() {
        assertThat(CsvWriter.escape("-12.5")).isEqualTo("-12.5");
        assertThat(CsvWriter.escape("+3")).isEqualTo("+3");
        assertThat(CsvWriter.escape("-1,5")).isEqualTo("\"-1,5\"");
        assertThat(CsvWriter.escape("1.5E-3")).isEqualTo("1.5E-3");
        assertThat(CsvWriter.escape("2026-08-01T10:00:00Z")).isEqualTo("2026-08-01T10:00:00Z");
        assertThat(CsvWriter.escape("plain text")).isEqualTo("plain text");
        assertThat(CsvWriter.escape("")).isEmpty();
        assertThat(CsvWriter.escape("a,b")).isEqualTo("\"a,b\"");
    }

    @Test
    @DisplayName("write() applies the guard to header-independent data rows")
    void writeAppliesGuard() {
        String csv = CsvWriter.write(List.of("note", "amount"), List.of(Arrays.asList("=1+1", -5)));
        assertThat(csv).isEqualTo("note,amount\r\n'=1+1,-5\r\n");
    }
}
