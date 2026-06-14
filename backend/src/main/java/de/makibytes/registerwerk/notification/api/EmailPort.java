package de.makibytes.registerwerk.notification.api;

import java.util.Map;

/**
 * Public e-mail sending port of the notification module.
 *
 * <p>Other modules must depend on this interface rather than the internal
 * {@code EmailService} — the SMTP adapter, template engine and error handling
 * remain encapsulated behind the module boundary.
 */
public interface EmailPort {

    /** Renders a Thymeleaf template and sends it as HTML (fire-and-forget). */
    void sendHtml(String to, String subject, String templateName, Map<String, Object> vars);

    /**
     * Renders a template and sends it with a PDF attachment.
     *
     * @return true if the message was handed to the SMTP server, false on failure —
     *         callers recording delivery status (e.g. §19 register statements)
     *         need the outcome.
     */
    boolean sendHtmlWithPdf(String to, String subject, String templateName,
                            Map<String, Object> vars, byte[] pdf, String pdfName);
}
