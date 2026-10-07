package de.makibytes.registerwerk.shared;

import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.regreporting.internal.ReportingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 5a: ONE production-mode switch. {@link ProductionMode} resolves the env var AND the
 * {@code -Dregisterwerk.production-mode} property; six classes used to read {@code System.getenv} or
 * {@code @Value("${REGISTERWERK_PRODUCTION_MODE}")} themselves, so a {@code -D} flag hardened some gates
 * and left the Travel Rule shared key, the trading venue gate, the regulatory-reporting guard and
 * e-mail identity linking open.
 */
class ProductionModeConsolidationTest {

    private static MockEnvironment dFlag() {
        // a -D system property and any other config source arrive as a plain property of this name
        return new MockEnvironment().withProperty(ProductionMode.PROPERTY_NAME, "true");
    }

    @Test
    @DisplayName("no class outside ProductionMode reads the production-mode env var / property itself")
    void nobodyReadsTheSwitchDirectly() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("ProductionMode.java")) {
                    continue;
                }
                String[] lines = Files.readString(p).split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue; // javadoc / comments may name the variable
                    }
                    if (line.contains("\"REGISTERWERK_PRODUCTION_MODE\"")
                            || line.contains("\"registerwerk.production-mode\"")
                            || line.contains("${REGISTERWERK_PRODUCTION_MODE")
                            || line.contains("${registerwerk.production-mode")) {
                        offenders.add(p + ":" + (i + 1) + "  " + line);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("read the switch through shared.ProductionMode (Environment-based), never System.getenv/@Value")
                .isEmpty();
    }

    @Test
    void helperHonoursEnvVarAndDashDProperty() {
        assertThat(ProductionMode.resolve(new MockEnvironment())).isFalse();
        assertThat(ProductionMode.resolve(dFlag())).isTrue();
        assertThat(ProductionMode.resolve(
                new MockEnvironment().withProperty(ProductionMode.ENV_NAME, " TRUE "))).isTrue();
    }

    @Test
    void emailLinkingWithoutVerificationIsRefusedUnderDashDProductionMode() {
        RegisterwerkAuthProperties props = new RegisterwerkAuthProperties();
        props.setEnvironment(new MockEnvironment());
        assertThat(props.linkByEmailWithoutVerificationAllowed()).isTrue();

        props.setEnvironment(dFlag());
        assertThat(props.linkByEmailWithoutVerificationAllowed()).isFalse();

        props.setLinkByEmailWithoutVerification(true); // an explicit operator override still wins
        assertThat(props.linkByEmailWithoutVerificationAllowed()).isTrue();
    }

    @Test
    void regReportingPrototypeIsRefusedUnderDashDProductionMode() throws Exception {
        ReportingProperties props = new ReportingProperties();
        props.setPrototypeEnabled(true);
        var ctor = Class.forName("de.makibytes.registerwerk.regreporting.internal.RegReportingProductionReadinessCheck")
                .getDeclaredConstructor(ReportingProperties.class, org.springframework.core.env.Environment.class);
        ctor.setAccessible(true);
        Object check = ctor.newInstance(props, dFlag());
        var method = check.getClass().getDeclaredMethod("check");
        method.setAccessible(true);
        assertThatThrownBy(() -> {
            try {
                method.invoke(check);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("PROTOTYPE");
    }
}
