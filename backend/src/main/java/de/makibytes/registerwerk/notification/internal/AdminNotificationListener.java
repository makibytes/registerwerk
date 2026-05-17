package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.admin.events.OperatorUserInvitedNotificationEvent;
import de.makibytes.registerwerk.admin.events.OperatorUserPasswordResetNotificationEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class AdminNotificationListener {

    private final CompanyUserInvitationEmailService invitationEmailService;
    private final PasswordResetEmailService passwordResetEmailService;

    AdminNotificationListener(
            CompanyUserInvitationEmailService invitationEmailService,
            PasswordResetEmailService passwordResetEmailService) {
        this.invitationEmailService = invitationEmailService;
        this.passwordResetEmailService = passwordResetEmailService;
    }

    @ApplicationModuleListener
    void on(OperatorUserInvitedNotificationEvent e) {
        invitationEmailService.sendInvite(e.email(), e.displayName(), null, e.inviteLink());
    }

    @ApplicationModuleListener
    void on(OperatorUserPasswordResetNotificationEvent e) {
        passwordResetEmailService.sendReset(e.email(), null, null, e.resetLink());
    }
}
