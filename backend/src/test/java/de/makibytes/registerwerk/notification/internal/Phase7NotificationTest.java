package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.admin.events.OperatorUserInvitedNotificationEvent;
import de.makibytes.registerwerk.admin.events.OperatorUserPasswordResetNotificationEvent;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.events.PrivilegedAccountChangedEvent;
import de.makibytes.registerwerk.notification.api.EmailDeliveryException;
import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.SecureLinkPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Notification Phase 7 (7A-03, 7A-04)")
class Phase7NotificationTest {

    private static TemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        TemplateEngine engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /** In-memory stand-in for the KEK: wrap = reverse the bytes. */
    private static SecureLinkPort links() {
        EnvelopeCipher cipher = new EnvelopeCipher(new EnvelopeCipher.KeyWrapper() {
            public String name() { return "test"; }
            public byte[] wrap(byte[] dek) { byte[] r = dek.clone(); for (int i = 0; i < r.length; i++) r[i] ^= 0x5A; return r; }
            public byte[] unwrap(byte[] w) { return wrap(w); }
        });
        return new SecureLinkPort() {
            public String seal(String link, UUID userId) { return cipher.encrypt(link, "u:" + userId); }
            public String open(String sealed, UUID userId) { return cipher.decrypt(sealed, "u:" + userId); }
        };
    }

    @Test
    @DisplayName("operator invite and reset events render a mail with the link (no NPE on missing names)")
    void inviteAndResetRender() throws Exception {
        SmtpEmailAdapter smtp = mock(SmtpEmailAdapter.class);
        EmailService email = new EmailService(smtp, engine(), new SimpleMeterRegistry());
        SecureLinkPort links = links();
        AdminNotificationListener listener = new AdminNotificationListener(
                new CompanyUserInvitationEmailService(email), new PasswordResetEmailService(email), links);
        UUID uid = UUID.randomUUID();

        listener.on(new OperatorUserInvitedNotificationEvent(uid, "a@x.org", "Anna", null,
                links.seal("https://app/register/tok123", uid)));
        listener.on(new OperatorUserPasswordResetNotificationEvent(uid, "a@x.org", null, null,
                links.seal("https://app/reset-password/tok456", uid)));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(smtp, org.mockito.Mockito.times(2)).sendHtml(anyString(), anyString(), body.capture());
        assertThat(body.getAllValues().get(0)).contains("https://app/register/tok123");
        assertThat(body.getAllValues().get(1)).contains("https://app/reset-password/tok456");
    }

    @Test
    @DisplayName("the published event carries no plaintext link")
    void eventCarriesNoPlaintextToken() {
        UUID uid = UUID.randomUUID();
        String sealed = links().seal("https://app/register/SECRETTOKEN", uid);
        String json = new OperatorUserInvitedNotificationEvent(uid, "a@x.org", "Anna", "Co", sealed).toString();
        assertThat(json).doesNotContain("SECRETTOKEN").doesNotContain("register/");
        assertThat(sealed).startsWith("enc:v1:");
    }

    @Test
    @DisplayName("an SMTP failure makes the privileged-account alert listener throw (publication stays incomplete)")
    void alertListenerRethrowsSmtpFailure() throws Exception {
        SmtpEmailAdapter smtp = mock(SmtpEmailAdapter.class);
        doThrow(new RuntimeException("smtp down")).when(smtp).sendHtml(anyString(), anyString(), anyString());
        EmailService email = new EmailService(smtp, engine(), new SimpleMeterRegistry());
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser other = new AppUser();
        other.setId(UUID.randomUUID());
        other.setEmail("other-admin@x.org");
        when(users.findEnabledWithRole(de.makibytes.registerwerk.auth.api.AppUserRole.REGISTRY_ADMIN)).thenReturn(List.of(other));
        PrivilegedAccountAlertListener listener = new PrivilegedAccountAlertListener(users, email);

        assertThatThrownBy(() -> listener.on(new PrivilegedAccountChangedEvent(UUID.randomUUID(), UUID.randomUUID(),
                "REGISTRY_ADMIN", "new@x.org", "INVITED", Set.of("REGISTRY_ADMIN"), false)))
                .isInstanceOf(EmailDeliveryException.class);
    }
}
