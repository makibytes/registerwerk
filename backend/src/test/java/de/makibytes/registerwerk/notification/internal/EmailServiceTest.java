package de.makibytes.registerwerk.notification.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the email-send-failure counter — no persisted state backs email delivery at
 * all (not even a fire-and-forget audit event, unlike every other job in this repo-wide
 * alerting metrics), so a Micrometer Counter is the only thing to expose.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailService — send-failure counter")
class EmailServiceTest {

    @Mock private SmtpEmailAdapter smtpEmailAdapter;
    @Mock private TemplateEngine templateEngine;

    private SimpleMeterRegistry meterRegistry;
    private EmailService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new EmailService(smtpEmailAdapter, templateEngine, meterRegistry);
        when(templateEngine.process(anyString(), any())).thenReturn("<html></html>");
    }

    private double counter(String context) {
        return meterRegistry.get("registerwerk_notification_email_send_failures_total")
                .tag("context", context)
                .counter()
                .count();
    }

    @Test
    @DisplayName("sendHtml failure increments the generic-context counter")
    void sendHtml_failure_incrementsGenericCounter() throws MessagingException {
        doThrow(new RuntimeException("SMTP down")).when(smtpEmailAdapter).sendHtml(anyString(), anyString(), anyString());

        service.sendHtml("investor@example.com", "Subject", "email/welcome", null);

        assertThat(counter("generic")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sendHtmlWithPdf failure increments the statement_pdf-context counter and returns false")
    void sendHtmlWithPdf_failure_incrementsStatementPdfCounter() throws MessagingException {
        doThrow(new RuntimeException("SMTP down")).when(smtpEmailAdapter)
                .sendHtml(anyString(), anyString(), anyString(), any(), anyString());

        boolean sent = service.sendHtmlWithPdf("investor@example.com", "Subject", "email/statement", null, new byte[]{1, 2, 3}, "statement.pdf");

        assertThat(sent).isFalse();
        assertThat(counter("statement_pdf")).isEqualTo(1.0);
        assertThat(meterRegistry.find("registerwerk_notification_email_send_failures_total")
                .tag("context", "generic").counter()).isNull();
    }

    @Test
    @DisplayName("successful sends do not register/increment either counter")
    void successfulSend_doesNotIncrementCounters() {
        service.sendHtml("investor@example.com", "Subject", "email/welcome", null);

        // Counter.builder(...).register(...) only ever runs inside the catch blocks, so a
        // successful send never even registers the meter — .find() (not .get(), which throws
        // when absent) is the correct way to assert "no failure was recorded."
        assertThat(meterRegistry.find("registerwerk_notification_email_send_failures_total").counter()).isNull();
    }
}
