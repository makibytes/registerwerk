package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.customer.events.CompanyUserInvitedEvent;
import de.makibytes.registerwerk.customer.events.CompanyUserPasswordResetRequestedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class CustomerNotificationListener {

    private final CompanyUserInvitationEmailService invitationEmailService;
    private final PasswordResetEmailService passwordResetEmailService;
    private final de.makibytes.registerwerk.shared.SecureLinkPort links;

    CustomerNotificationListener(
            CompanyUserInvitationEmailService invitationEmailService,
            PasswordResetEmailService passwordResetEmailService,
            de.makibytes.registerwerk.shared.SecureLinkPort links) {
        this.links = links;
        this.invitationEmailService = invitationEmailService;
        this.passwordResetEmailService = passwordResetEmailService;
    }

    @ApplicationModuleListener
    void on(CompanyUserInvitedEvent e) {
        invitationEmailService.sendInvite(e.email(), e.displayName(), null, links.open(e.inviteLink(), e.userId()));
    }

    @ApplicationModuleListener
    void on(CompanyUserPasswordResetRequestedEvent e) {
        passwordResetEmailService.sendReset(e.email(), null, null, links.open(e.resetLink(), e.userId()));
    }
}
