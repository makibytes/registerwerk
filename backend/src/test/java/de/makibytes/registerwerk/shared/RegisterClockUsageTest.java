package de.makibytes.registerwerk.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 5a: a register day ("today" for acquisition dates, statement dates, start/maturity checks) comes from
 * the injected {@link RegisterClock}, not from the JVM zone ({@code LocalDate.now()}) or a hard-coded UTC
 * ({@code LocalDate.now(ZoneOffset.UTC)}): containers run in UTC, which rolls the register day over an hour
 * or two late, and a fixed clock in tests cannot reach a direct call. Purely technical timestamps
 * ({@code Instant.now()} for created/updated stamps, metrics, TTLs) are deliberately not covered.
 */
class RegisterClockUsageTest {

    private static final List<String> MODULES =
            List.of("repo", "trading", "corporateactions", "asset", "registertransfer");

    @Test
    @DisplayName("repo/trading/corporateactions/asset/registertransfer never call LocalDate.now / LocalDateTime.now directly")
    void businessDatesComeFromTheRegisterClock() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String module : MODULES) {
            Path root = Path.of("src/main/java/de/makibytes/registerwerk", module);
            assertThat(root).as("module directory %s", root).isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String[] lines = Files.readString(p).split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                            continue;
                        }
                        if (line.contains("LocalDate.now(") || line.contains("LocalDateTime.now(")) {
                            offenders.add(p + ":" + (i + 1) + "  " + line);
                        }
                    }
                }
            }
        }
        assertThat(offenders).as("use the injected shared.RegisterClock#today()").isEmpty();
    }
}
