package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.customer.events.CompanyUserInvitedEvent;
import de.makibytes.registerwerk.customer.events.CompanyUserPasswordResetRequestedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class CustomerNotificationListener {

    private final CompanyUserInvitationEmailService invitationEmailService;
    private final PasswordResetEmailService passwordResetEmailService;

    CustomerNotificationListener(
            CompanyUserInvitationEmailService invitationEmailService,
            PasswordResetEmailService passwordResetEmailService) {
        this.invitationEmailService = invitationEmailService;
        this.passwordResetEmailService = passwordResetEmailService;
    }

    @ApplicationModuleListener
    void on(CompanyUserInvitedEvent e) {
        invitationEmailService.sendInvite(e.email(), e.displayName(), null, e.inviteLink());
    }

    @ApplicationModuleListener
    void on(CompanyUserPasswordResetRequestedEvent e) {
        passwordResetEmailService.sendReset(e.email(), null, null, e.resetLink());
    }
}
