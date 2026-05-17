package de.makibytes.registerwerk.notification.internal;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CompanyUserInvitationEmailService {

    private final EmailService emailService;

    public CompanyUserInvitationEmailService(EmailService emailService) {
        this.emailService = emailService;
    }

    public void sendInvite(String to, String inviteeName, String entityName, String registrationUrl) {
        emailService.sendHtml(
            to,
            "Your Registerwerk account invitation",
            "email/company-user-invite",
            Map.of(
                "inviteeName", inviteeName,
                "entityName", entityName,
                "registrationUrl", registrationUrl
            )
        );
    }
}
