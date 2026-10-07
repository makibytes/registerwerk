package de.makibytes.registerwerk.notification.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.dora.events.IctIncidentDeadlineBreachedEvent;
import de.makibytes.registerwerk.notification.api.EmailDeliveryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wave 1 docker gate: with the placeholder SMTP host the DORA breach alert failed on every boot and
 * the incomplete event publication was re-submitted with an ERROR stack trace each time. Outside
 * production that is one WARN line; in production mode the failure must still propagate (P7A-04).
 */
@DisplayName("DoraDeadlineAlertListener: delivery failure handling")
class DoraDeadlineAlertListenerTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final EmailService email = mock(EmailService.class);
    private final IctIncidentDeadlineBreachedEvent event =
            new IctIncidentDeadlineBreachedEvent(UUID.randomUUID(), "Payment outage", "INITIAL_NOTIFICATION",
                    Instant.parse("2026-01-01T00:00:00Z"));
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        AppUser admin = mock(AppUser.class);
        when(admin.getEmail()).thenReturn("admin@local");
        when(users.findEnabledWithRole(AppUserRole.REGISTRY_ADMIN)).thenReturn(List.of(admin));
        doThrow(new EmailDeliveryException("Failed to send email template=email/dora-deadline-breach",
                new IllegalStateException("Mail server connection failed: smtp.example.com")))
                .when(email).sendHtmlOrThrow(anyString(), anyString(), anyString(), any(Map.class));
        logger = (Logger) LoggerFactory.getLogger(DoraDeadlineAlertListener.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("outside production a delivery failure is one WARN line without a stack trace and does not rethrow")
    void nonProductionDegradesToSingleWarn() {
        DoraDeadlineAlertListener listener = listener(false);

        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();

        List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(appender.list).noneMatch(e -> e.getThrowableProxy() != null);
        assertThat(warns.get(0).getFormattedMessage()).contains("DORA").contains("smtp.example.com");
    }

    @Test
    @DisplayName("in production mode the failure still propagates so the publication stays incomplete (P7A-04)")
    void productionStillThrows() {
        DoraDeadlineAlertListener listener = listener(true);

        assertThatThrownBy(() -> listener.on(event)).isInstanceOf(EmailDeliveryException.class);
    }

    private DoraDeadlineAlertListener listener(boolean production) {
        MockEnvironment env = new MockEnvironment();
        if (production) env.setProperty("registerwerk.production-mode", "true");
        return new DoraDeadlineAlertListener(users, email, env);
    }
}
