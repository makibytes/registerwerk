package de.makibytes.registerwerk.notification.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Low-level SMTP adapter that sends HTML emails via JavaMailSender.
 */
@Component
public class SmtpEmailAdapter {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailAdapter.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailAdapter(
            JavaMailSender mailSender,
            @Value("${registerwerk.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * Sends an HTML email.
     *
     * @param to       recipient email address
     * @param subject  email subject line
     * @param htmlBody rendered HTML content
     * @throws MessagingException if the message cannot be sent
     */
    public void sendHtml(String to, String subject, String htmlBody) throws MessagingException {
        sendHtml(to, subject, htmlBody, null, null);
    }

    /**
     * Sends an HTML email, optionally with a single binary attachment.
     *
     * @param attachment      attachment bytes, or {@code null} for no attachment
     * @param attachmentName  file name for the attachment (required if attachment present)
     */
    public void sendHtml(String to, String subject, String htmlBody,
                         byte[] attachment, String attachmentName) throws MessagingException {
        log.debug("Sending email to={}, subject={}, hasAttachment={}", to, subject, attachment != null);
        MimeMessage message = mailSender.createMimeMessage();
        boolean multipart = attachment != null;
        MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        if (multipart) {
            helper.addAttachment(attachmentName,
                    new org.springframework.core.io.ByteArrayResource(attachment),
                    "application/pdf");
        }
        mailSender.send(message);
        log.info("Email sent to={}", to);
    }
}
