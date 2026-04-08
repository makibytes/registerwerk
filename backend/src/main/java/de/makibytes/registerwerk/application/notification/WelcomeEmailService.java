package de.makibytes.registerwerk.application.notification;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Sends welcome emails to newly onboarded legal entities.
 */
@Service
public class WelcomeEmailService {

    private final EmailService emailService;

    public WelcomeEmailService(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Sends a welcome email with links to the frontend and API documentation.
     *
     * @param to          recipient email address
     * @param entityName  legal name of the entity
     * @param frontendUrl URL to the registry frontend
     * @param apiDocsUrl  URL to the API documentation
     */
    public void sendWelcome(String to, String entityName, String frontendUrl, String apiDocsUrl) {
        Map<String, Object> vars = Map.of(
            "entityName", entityName,
            "frontendUrl", frontendUrl,
            "apiDocsUrl", apiDocsUrl
        );
        emailService.sendHtml(to, "Welcome to Registerwerk", "email/welcome", vars);
    }
}
