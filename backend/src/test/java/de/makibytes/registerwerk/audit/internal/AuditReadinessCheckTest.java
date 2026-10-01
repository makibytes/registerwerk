package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@DisplayName("AuditReadinessCheck (6-11, T6-17 interim)")
class AuditReadinessCheckTest {

    private AuditReadinessCheck check(boolean owns, boolean signing, boolean ack) {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(any(String.class), eq(Boolean.class))).thenReturn(owns);
        AuditReadinessCheck c = new AuditReadinessCheck(jdbc,
                signing ? Optional.of(Mockito.mock(SigningKeyProvider.class)) : Optional.empty(),
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(c, "allowOwnerRuntimeRole", ack);
        return c;
    }

    @Test
    @DisplayName("production: runtime role owning audit_event fails the check unless acknowledged")
    void ownerFailsInProduction() {
        assertThatThrownBy(() -> check(true, true, false).check(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("owns audit_event");
        assertThatCode(() -> check(true, true, true).check(true)).doesNotThrowAnyException();
        assertThatCode(() -> check(false, true, false).check(true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("production: a missing signing key fails; non-production only warns")
    void signingRequired() {
        assertThatThrownBy(() -> check(false, false, false).check(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("signing");
        assertThatCode(() -> check(true, false, false).check(false)).doesNotThrowAnyException();
    }
}
