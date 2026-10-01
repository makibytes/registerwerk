package de.makibytes.registerwerk.shared;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DocumentSigningService in production mode (7A-02, T7-01 interim)")
class DocumentSigningProductionTest {

    private DocumentSigningService service(String path, boolean allowUnsigned, boolean production) {
        return new DocumentSigningService(path, "", "", "", allowUnsigned, new org.springframework.mock.env.MockEnvironment().withProperty("REGISTERWERK_PRODUCTION_MODE", String.valueOf(production)),
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("production without a keystore is refused unless unsigned output is explicitly allowed")
    void refusedInProduction() {
        assertThatThrownBy(() -> service("", false, true).init()).hasMessageContaining("keystore");
        assertThatThrownBy(() -> service("/nonexistent.p12", false, true).init()).hasMessageContaining("keystore");
        assertThatCode(() -> service("", true, true).init()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-production keeps rendering unsigned (demo stack)")
    void demoUnchanged() {
        assertThatCode(() -> service("", false, false).init()).doesNotThrowAnyException();
    }
}
