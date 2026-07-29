package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.notification.internal.SmtpEmailAdapter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;

/**
 * High-level email service that merges Thymeleaf templates and dispatches via SMTP.
 */
@Service
public class EmailService implements de.makibytes.registerwerk.notification.api.EmailPort {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final SmtpEmailAdapter smtpEmailAdapter;
    private final TemplateEngine templateEngine;
    private final MeterRegistry meterRegistry;

    public EmailService(SmtpEmailAdapter smtpEmailAdapter, TemplateEngine templateEngine, MeterRegistry meterRegistry) {
        this.smtpEmailAdapter = smtpEmailAdapter;
        this.templateEngine = templateEngine;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Renders a Thymeleaf template and sends the resulting HTML as an email.
     *
     * @param to           recipient email address
     * @param subject      email subject line
     * @param templateName template path relative to the templates root (e.g. "email/welcome")
     * @param vars         model variables passed to the template
     */
    @Override
    public void sendHtml(String to, String subject, String templateName, Map<String, Object> vars) {
        Context context = new Context(Locale.ENGLISH);
        if (vars != null) {
            vars.forEach(context::setVariable);
        }
        String htmlBody = templateEngine.process(templateName, context);
        try {
            smtpEmailAdapter.sendHtml(to, subject, htmlBody);
        } catch (Exception e) {
            log.error("Failed to send email to={}, template={} — SMTP error is non-fatal", to, templateName, e);
            incrementFailureCounter("generic");
        }
    }

    /**
     * Renders a template and sends it with a PDF attachment.
     *
     * @return true if the message was sent, false if delivery failed. Unlike the
     *         fire-and-forget overload above, the caller needs the outcome to
     *         record §19 statement delivery status, so failures are signalled.
     */
    @Override
    public boolean sendHtmlWithPdf(String to, String subject, String templateName,
                                   Map<String, Object> vars, byte[] pdf, String pdfName) {
        Context context = new Context(Locale.ENGLISH);
        if (vars != null) {
            vars.forEach(context::setVariable);
        }
        String htmlBody = templateEngine.process(templateName, context);
        try {
            smtpEmailAdapter.sendHtml(to, subject, htmlBody, pdf, pdfName);
            return true;
        } catch (Exception e) {
            log.error("Failed to send statement email to={} — {}", to, e.getMessage());
            incrementFailureCounter("statement_pdf");
            return false;
        }
    }

    /** No persisted state backs email delivery at all (not even a fire-and-forget audit event),
     *  so a Counter — not a gauge — is the only thing to expose (repo-wide alerting-gap
     *  follow-up). Tagged by context to keep the fire-and-forget path (sendHtml) distinguishable
     *  from the §19-statement-delivery path (sendHtmlWithPdf), whose caller actually needs the
     *  boolean outcome. */
    private void incrementFailureCounter(String context) {
        Counter.builder("registerwerk_notification_email_send_failures_total")
                .tag("context", context)
                .description("Count of email send failures since startup, tagged by context (generic/statement_pdf)")
                .register(meterRegistry)
                .increment();
    }
}
