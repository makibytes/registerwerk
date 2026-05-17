package de.makibytes.registerwerk.notification.internal;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PasswordResetEmailService {

    private final EmailService emailService;

    public PasswordResetEmailService(EmailService emailService) {
        this.emailService = emailService;
    }

    public void sendReset(String to, String recipientName, String entityName, String resetUrl) {
        emailService.sendHtml(
            to,
            "Reset your Registerwerk password",
            "email/password-reset",
            Map.of(
                "recipientName", recipientName,
                "entityName", entityName,
                "resetUrl", resetUrl
            )
        );
    }
}
