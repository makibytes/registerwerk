package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.admin.events.OperatorUserInvitedNotificationEvent;
import de.makibytes.registerwerk.admin.events.OperatorUserPasswordResetNotificationEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class AdminNotificationListener {

    private final CompanyUserInvitationEmailService invitationEmailService;
    private final PasswordResetEmailService passwordResetEmailService;
    private final de.makibytes.registerwerk.shared.SecureLinkPort links;

    AdminNotificationListener(
            CompanyUserInvitationEmailService invitationEmailService,
            PasswordResetEmailService passwordResetEmailService,
            de.makibytes.registerwerk.shared.SecureLinkPort links) {
        this.links = links;
        this.invitationEmailService = invitationEmailService;
        this.passwordResetEmailService = passwordResetEmailService;
    }

    @ApplicationModuleListener
    void on(OperatorUserInvitedNotificationEvent e) {
        invitationEmailService.sendInvite(e.email(), e.displayName(), e.entityName(), links.open(e.inviteLink(), e.userId()));
    }

    @ApplicationModuleListener
    void on(OperatorUserPasswordResetNotificationEvent e) {
        passwordResetEmailService.sendReset(e.email(), e.displayName(), e.entityName(), links.open(e.resetLink(), e.userId()));
    }
}
