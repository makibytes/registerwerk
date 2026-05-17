package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.onboarding.events.OnboardingCompletedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class OnboardingNotificationListener {

    private final WelcomeEmailService welcomeEmailService;

    OnboardingNotificationListener(WelcomeEmailService welcomeEmailService) {
        this.welcomeEmailService = welcomeEmailService;
    }

    @ApplicationModuleListener
    void on(OnboardingCompletedEvent e) {
        welcomeEmailService.sendWelcome(e.adminEmail(), e.entityName(), e.frontendUrl() + "/login", e.frontendUrl());
    }
}
