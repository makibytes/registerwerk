package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.deployment.api.BondLifecycleTransitionEvent;
import de.makibytes.registerwerk.deployment.api.CouponLifecycleTransitionEvent;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.notification.api.EmailPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BondLifecycleNotificationListener (9A-04R)")
class BondLifecycleNotificationListenerTest {

    @Mock private EmailPort emailPort;
    @Mock private AppUserRepository appUserRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private AssetRepository assetRepository;

    private final UUID assetId = UUID.randomUUID();
    private final UUID issuerId = UUID.randomUUID();

    private BondLifecycleNotificationListener listener() {
        return new BondLifecycleNotificationListener(emailPort, appUserRepository, legalEntityRepository, assetRepository);
    }

    private void issuerWithOneAdmin() {
        Asset asset = new Asset();
        asset.setIssuerId(issuerId);
        asset.setName("Test Bond");
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        AppUser admin = new AppUser();
        admin.setEmail("admin@issuer.example");
        admin.setRoles(EnumSet.of(AppUserRole.COMPANY_ADMIN));
        when(appUserRepository.findByLegalEntityIdOrderByFullNameAscEmailAsc(issuerId)).thenReturn(List.of(admin));
    }

    @Test
    @DisplayName("OVERDUE and DEFAULTED mail the issuer's company admins")
    void overdueAndDefaultedAreMailed() {
        issuerWithOneAdmin();
        listener().on(new BondLifecycleTransitionEvent(assetId, BondStatus.MATURED, BondStatus.DEFAULTED, "x",
                List.of(), LocalDate.now(), 7));
        verify(emailPort).sendHtml(eq("admin@issuer.example"), anyString(), eq("bond-lifecycle-status"), any());
    }

    @Test
    @DisplayName("MATURED is routine and not mailed")
    void maturedIsNotMailed() {
        listener().on(new BondLifecycleTransitionEvent(assetId, BondStatus.ACTIVE, BondStatus.MATURED, "x",
                List.of(), null, null));
        verifyNoInteractions(emailPort, assetRepository);
    }

    @Test
    @DisplayName("a missed coupon mails the issuer's company admins")
    void missedCouponIsMailed() {
        issuerWithOneAdmin();
        listener().on(new CouponLifecycleTransitionEvent(UUID.randomUUID(), assetId, CouponStatus.OVERDUE,
                CouponStatus.MISSED, "x", UUID.randomUUID(), 30));
        verify(emailPort).sendHtml(eq("admin@issuer.example"), anyString(), eq("bond-lifecycle-status"), any());
    }
}
