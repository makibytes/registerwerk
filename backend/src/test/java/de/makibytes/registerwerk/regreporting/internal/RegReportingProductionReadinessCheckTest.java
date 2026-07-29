package de.makibytes.registerwerk.regreporting.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the reporting prototype remains default-off and is labelled honestly outside
 * production. A package-private supplier seam exercises production behavior without mutating
 * process-wide environment state.
 */
@DisplayName("RegReportingProductionReadinessCheck unit tests")
class RegReportingProductionReadinessCheckTest {

    @Test
    @DisplayName("disabled prototype outside production mode does not throw")
    void disabledPrototype_outsideProduction_doesNotThrow() {
        ReportingProperties props = new ReportingProperties();
        RegReportingProductionReadinessCheck check = new RegReportingProductionReadinessCheck(props);

        assertThatCode(check::check).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SFTP does not change the non-production prototype warning semantics")
    void sftpPrototype_outsideProduction_doesNotThrow() {
        ReportingProperties props = new ReportingProperties();
        props.setGateway("SFTP");
        props.setPrototypeEnabled(true);
        RegReportingProductionReadinessCheck check = new RegReportingProductionReadinessCheck(props);

        assertThatCode(check::check).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NOOP prototype outside production remains an explicitly enabled draft")
    void noopPrototype_outsideProduction_doesNotThrow() {
        ReportingProperties props = new ReportingProperties();
        props.setPrototypeEnabled(true);
        RegReportingProductionReadinessCheck check = new RegReportingProductionReadinessCheck(props);

        assertThatCode(check::check).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enabled prototype fails closed in production regardless of gateway")
    void enabledPrototype_inProduction_throws() {
        ReportingProperties props = new ReportingProperties();
        props.setGateway("SFTP");
        props.setPrototypeEnabled(true);
        RegReportingProductionReadinessCheck check =
                new RegReportingProductionReadinessCheck(props, () -> true);

        assertThatThrownBy(check::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SFTP proves byte transport only");
    }

    @Test
    @DisplayName("disabled prototype permits the rest of the application to run in production")
    void disabledPrototype_inProduction_doesNotThrow() {
        ReportingProperties props = new ReportingProperties();
        RegReportingProductionReadinessCheck check =
                new RegReportingProductionReadinessCheck(props, () -> true);

        assertThatCode(check::check).doesNotThrowAnyException();
    }
}
