package de.makibytes.registerwerk.stepup.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Approval queue: programmatic DualControlGate reasons")
class GateReasonCatalogTest {

    private static final Pattern GATE_CALL =
            Pattern.compile("(?:dualControlGate\\.require(?:IfNotBootstrap)?|requirePrivilegedApproval)\\(\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("every reason passed to DualControlGate in the sources is in the catalog's list, and the list has nothing else")
    void gateReasonListMatchesTheSources() throws Exception {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = GATE_CALL.matcher(Files.readString(p));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        assertThat(found).as("the scan must find the gated actions").isNotEmpty();
        assertThat(RouteApprovalActionCatalog.GATE_REASONS).containsExactlyInAnyOrderElementsOf(found);
    }
}
