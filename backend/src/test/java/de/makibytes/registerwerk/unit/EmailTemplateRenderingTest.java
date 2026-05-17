package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.notification.internal.EmailService;
import de.makibytes.registerwerk.notification.internal.OnboardingEmailService;
import de.makibytes.registerwerk.notification.internal.WelcomeEmailService;
import de.makibytes.registerwerk.notification.internal.SmtpEmailAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("Email template rendering")
class EmailTemplateRenderingTest {

    private WelcomeEmailService welcomeEmailService;

    private OnboardingEmailService onboardingEmailService;

    private SmtpEmailAdapter smtpEmailAdapter;

    @BeforeEach
    void setUp() {
        smtpEmailAdapter = mock(SmtpEmailAdapter.class);

        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setCacheable(false);

        TemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);

        EmailService emailService = new EmailService(smtpEmailAdapter, templateEngine);
        welcomeEmailService = new WelcomeEmailService(emailService);
        onboardingEmailService = new OnboardingEmailService(emailService);
    }

    @Test
    @DisplayName("welcome email template should render with the configured email path")
    void welcomeEmailTemplate_shouldRender() throws Exception {
        welcomeEmailService.sendWelcome(
            "admin@test.local",
            "Acme AG",
            "http://localhost:4201/login",
            "http://localhost:4201/api-docs"
        );

        String htmlBody = captureHtmlBody("admin@test.local", "Welcome to Registerwerk");

        assertThat(htmlBody)
            .contains("Welcome to the Registerwerk")
            .contains("Acme AG")
            .contains("http://localhost:4201/login")
            .contains("http://localhost:4201/api-docs");
    }

    @Test
    @DisplayName("onboarding invite template should render with the configured email path")
    void onboardingInviteTemplate_shouldRender() throws Exception {
        onboardingEmailService.sendInvite(
            "contact@test.local",
            "Beta GmbH",
            "http://localhost:4201/onboarding?token=cleartext-token"
        );

        String htmlBody = captureHtmlBody("contact@test.local", "Your Registerwerk Onboarding Invitation");

        assertThat(htmlBody)
            .contains("Beta GmbH")
            .contains("http://localhost:4201/onboarding?token=cleartext-token");
    }

    private String captureHtmlBody(String recipient, String subject) throws Exception {
        var htmlBodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(smtpEmailAdapter).sendHtml(eq(recipient), eq(subject), htmlBodyCaptor.capture());
        return htmlBodyCaptor.getValue();
    }
}
