package de.makibytes.registerwerk.application.notification;

import de.makibytes.registerwerk.infrastructure.email.SmtpEmailAdapter;
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
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final SmtpEmailAdapter smtpEmailAdapter;
    private final TemplateEngine templateEngine;

    public EmailService(SmtpEmailAdapter smtpEmailAdapter, TemplateEngine templateEngine) {
        this.smtpEmailAdapter = smtpEmailAdapter;
        this.templateEngine = templateEngine;
    }

    /**
     * Renders a Thymeleaf template and sends the resulting HTML as an email.
     *
     * @param to           recipient email address
     * @param subject      email subject line
     * @param templateName template path relative to the templates root (e.g. "email/welcome")
     * @param vars         model variables passed to the template
     */
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
        }
    }
}
