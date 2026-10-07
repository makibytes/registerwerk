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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

@DisplayName("AuditReadinessCheck (6-11, T6-17 interim)")
class AuditReadinessCheckTest {

    private AuditReadinessCheck check(boolean owns, boolean signing, boolean ack) {
        return check(owns, false, signing, ack);
    }

    private AuditReadinessCheck check(boolean owns, boolean mutates, boolean signing, boolean ack) {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject(contains("pg_has_role"), eq(Boolean.class))).thenReturn(owns);
        Mockito.when(jdbc.queryForObject(contains("has_table_privilege"), eq(Boolean.class))).thenReturn(mutates);
        Mockito.when(jdbc.queryForObject(contains("current_user"), eq(String.class))).thenReturn("registerwerk_x");
        AuditReadinessCheck c = new AuditReadinessCheck(jdbc,
                signing ? Optional.of(Mockito.mock(SigningKeyProvider.class)) : Optional.empty(),
                new SimpleMeterRegistry(), new org.springframework.mock.env.MockEnvironment());
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
    @DisplayName("the owner failure message names the failure mode, the login and the fix (T6-17)")
    void ownerMessageExplainsFailureModeAndFix() {
        assertThatThrownBy(() -> check(true, true, false).check(true))
                .hasMessageContaining("registerwerk_x")
                .hasMessageContaining("DISABLE TRIGGER")
                .hasMessageContaining("DB_APP_USER")
                .hasMessageContaining("spring.flyway.user")
                .hasMessageContaining("allow-owner-runtime-role");
    }

    @Test
    @DisplayName("production: a non-owner that can still UPDATE/DELETE/TRUNCATE audit_event or run DDL fails, and the ack does not cover it")
    void privilegedNonOwnerFails() {
        assertThatThrownBy(() -> check(false, true, true, false).check(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UPDATE/DELETE/TRUNCATE");
        assertThatThrownBy(() -> check(false, true, true, true).check(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UPDATE/DELETE/TRUNCATE");
        assertThatCode(() -> check(false, true, true, false).check(false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("gauge is 1 for an owner or a privileged non-owner and 0 for a properly restricted login")
    void gaugeReflectsWeakLogin() {
        AuditReadinessCheck ok = check(false, false, true, false);
        ok.check(false);
        AuditReadinessCheck weak = check(false, true, true, false);
        weak.check(false);
        org.assertj.core.api.Assertions.assertThat(ReflectionTestUtils.getField(ok, "ownsGauge").toString()).isEqualTo("0");
        org.assertj.core.api.Assertions.assertThat(ReflectionTestUtils.getField(weak, "ownsGauge").toString()).isEqualTo("1");
    }

    @Test
    @DisplayName("production: a missing signing key fails; non-production only warns")
    void signingRequired() {
        assertThatThrownBy(() -> check(false, false, false).check(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("signing");
        assertThatCode(() -> check(true, false, false).check(false)).doesNotThrowAnyException();
    }
}
