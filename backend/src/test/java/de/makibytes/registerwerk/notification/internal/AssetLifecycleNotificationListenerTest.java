package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.AssetApprovedEvent;
import de.makibytes.registerwerk.asset.events.AssetRejectedEvent;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetLifecycleNotificationListener unit tests (Track 5-3)")
class AssetLifecycleNotificationListenerTest {

    @Mock private EmailPort emailPort;
    @Mock private AppUserRepository appUserRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private AssetRepository assetRepository;

    private AssetLifecycleNotificationListener listener;
    private final UUID assetId = UUID.randomUUID();
    private final UUID issuerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new AssetLifecycleNotificationListener(emailPort, appUserRepository, legalEntityRepository, assetRepository);
    }

    private Asset asset() {
        Asset a = new Asset();
        a.setIssuerId(issuerId);
        a.setName("Test Bond");
        return a;
    }

    private AppUser companyAdmin() {
        AppUser user = new AppUser();
        user.setEmail("admin@issuer.example");
        user.setRoles(EnumSet.of(AppUserRole.COMPANY_ADMIN));
        return user;
    }

    @Test
    @DisplayName("AssetApprovedEvent emails the issuer's company admins")
    void on_assetApproved_emailsIssuerAdmins() {
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset()));
        when(legalEntityRepository.findById(issuerId)).thenReturn(Optional.of(new LegalEntity()));
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(issuerId)).thenReturn(List.of(companyAdmin()));

        listener.on(new AssetApprovedEvent(assetId, UUID.randomUUID(), null));

        ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailPort).sendHtml(eq("admin@issuer.example"), anyString(), eq("asset-approved"), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("assetName", "Test Bond");
    }

    @Test
    @DisplayName("AssetRejectedEvent emails the issuer's company admins with the rejection reason")
    void on_assetRejected_emailsIssuerAdminsWithReason() {
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset()));
        when(legalEntityRepository.findById(issuerId)).thenReturn(Optional.of(new LegalEntity()));
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(issuerId)).thenReturn(List.of(companyAdmin()));

        listener.on(new AssetRejectedEvent(assetId, UUID.randomUUID(), null, "ISIN missing"));

        ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailPort).sendHtml(anyString(), anyString(), eq("asset-rejected"), varsCaptor.capture());
        assertThat(varsCaptor.getValue()).containsEntry("reason", "ISIN missing");
    }

    @Test
    @DisplayName("A deleted/unknown asset is silently skipped, not an error")
    void on_assetApproved_unknownAsset_doesNothing() {
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        listener.on(new AssetApprovedEvent(assetId, UUID.randomUUID(), null));

        verifyNoInteractions(emailPort);
    }
}
