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
        log.debug("Sending email to={}, subject={}", to, subject);
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        mailSender.send(message);
        log.info("Email sent to={}", to);
    }
}
