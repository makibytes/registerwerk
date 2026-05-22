package de.makibytes.registerwerk.notification.internal;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Sends onboarding invitation emails containing a secure one-time token link.
 */
@Service
public class OnboardingEmailService {

    private final EmailService emailService;

    public OnboardingEmailService(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Sends an onboarding invitation with the one-time URL containing the cleartext token.
     *
     * @param to            recipient email address
     * @param entityName    legal name of the entity being onboarded
     * @param onboardingUrl full URL including the token (e.g. https://app.registerwerk.io/onboarding?token=xyz)
     */
    public void sendInvite(String to, String entityName, String onboardingUrl) {
        Map<String, Object> vars = Map.of(
            "entityName", entityName,
            "onboardingUrl", onboardingUrl
        );
        emailService.sendHtml(to, "Your Registerwerk Onboarding Invitation", "email/onboarding-invite", vars);
    }
}
