package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.events.KycApprovedEvent;
import de.makibytes.registerwerk.kyc.events.KycExpiringEvent;
import de.makibytes.registerwerk.kyc.events.KycRejectedEvent;
import de.makibytes.registerwerk.notification.api.EmailPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KycNotificationListener unit tests (Track 5-3)")
class KycNotificationListenerTest {

    @Mock private EmailPort emailPort;
    @Mock private AppUserRepository appUserRepository;
    @Mock private LegalEntityRepository legalEntityRepository;

    private KycNotificationListener listener;
    private final UUID entityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new KycNotificationListener(emailPort, appUserRepository, legalEntityRepository);
        LegalEntity entity = new LegalEntity();
        entity.setCurrentName("Acme GmbH");
        when(legalEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
    }

    private AppUser companyAdmin() {
        AppUser user = new AppUser();
        user.setEmail("admin@acme.example");
        user.setFullName("Alice Admin");
        user.setRoles(EnumSet.of(AppUserRole.COMPANY_ADMIN));
        return user;
    }

    @Test
    @DisplayName("KycApprovedEvent emails every company admin with the entity name and expiry date")
    void on_kycApproved_emailsCompanyAdmins() {
        AppUser admin = companyAdmin();
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId)).thenReturn(List.of(admin));

        listener.on(new KycApprovedEvent(entityId, UUID.randomUUID(), null, Map.of("expiryDate", "2027-01-01")));

        ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailPort).sendHtml(org.mockito.ArgumentMatchers.eq("admin@acme.example"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("kyc-approved"), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("entityName", "Acme GmbH").containsEntry("expiryDate", "2027-01-01");
    }

    @Test
    @DisplayName("KycRejectedEvent emails company admins the kyc-rejected template with the reason")
    void on_kycRejected_emailsCompanyAdmins() {
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId)).thenReturn(List.of(companyAdmin()));

        listener.on(new KycRejectedEvent(entityId, UUID.randomUUID(), null, Map.of("reason", "Document expired")));

        ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailPort).sendHtml(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("kyc-rejected"), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("reason", "Document expired");
    }

    @Test
    @DisplayName("KycExpiringEvent marks 'expired' true only when reason is EXPIRED")
    void on_kycExpiring_setsExpiredFlagCorrectly() {
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId)).thenReturn(List.of(companyAdmin()));

        listener.on(new KycExpiringEvent(entityId, null, Map.of("reason", "EXPIRING_SOON")));

        ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailPort).sendHtml(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("kyc-expiring"), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("expired", false);
    }

    @Test
    @DisplayName("Non-COMPANY_ADMIN users are not emailed")
    void on_kycApproved_skipsNonCompanyAdmins() {
        AppUser investor = new AppUser();
        investor.setEmail("investor@acme.example");
        investor.setRoles(EnumSet.of(AppUserRole.INVESTOR));
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(entityId)).thenReturn(List.of(investor));

        listener.on(new KycApprovedEvent(entityId, UUID.randomUUID(), null, Map.of()));

        verifyNoInteractions(emailPort);
    }
}
