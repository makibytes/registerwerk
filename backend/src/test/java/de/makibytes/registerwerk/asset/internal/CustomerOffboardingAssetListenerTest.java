package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrant;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerOffboardingAssetListener unit tests")
class CustomerOffboardingAssetListenerTest {

    @Mock private AssetTokenAdminGrantRepository grantRepository;
    @Mock private AssetRepository assetRepository;

    @InjectMocks
    private CustomerOffboardingAssetListener listener;

    @Test
    @DisplayName("revokes every ACTIVE ASSET_TOKEN_ADMIN grant held by the offboarded entity")
    void revokesActiveGrants() {
        UUID entityId = UUID.randomUUID();
        AssetTokenAdminGrant grant = new AssetTokenAdminGrant();
        grant.setEntityId(entityId);
        when(grantRepository.findByEntityIdAndStatus(entityId, AssetTokenAdminGrant.Status.ACTIVE))
                .thenReturn(List.of(grant));
        when(assetRepository.findByIssuerId(entityId)).thenReturn(List.of());

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "exit"));

        assertThat(grant.getStatus()).isEqualTo(AssetTokenAdminGrant.Status.REVOKED);
        assertThat(grant.getRevokeReason()).contains("exit");
        verify(grantRepository).save(grant);
    }

    @Test
    @DisplayName("does not touch assets where the entity is not the issuer, only logs live ones it is")
    void doesNotMutateAssets() {
        UUID entityId = UUID.randomUUID();
        Asset issuedAsset = new Asset();
        issuedAsset.setStatus(AssetStatus.ISSUED);
        Asset draftAsset = new Asset();
        draftAsset.setStatus(AssetStatus.DRAFT);
        when(grantRepository.findByEntityIdAndStatus(entityId, AssetTokenAdminGrant.Status.ACTIVE)).thenReturn(List.of());
        when(assetRepository.findByIssuerId(entityId)).thenReturn(List.of(issuedAsset, draftAsset));

        listener.onCustomerOffboarded(new CustomerOffboardedEvent(entityId, UUID.randomUUID(), "REGISTRY_ADMIN", "exit"));

        // No mutation methods called on Asset/AssetRepository beyond the read — this listener
        // only logs; it never calls assetRepository.save(...).
        verify(assetRepository, org.mockito.Mockito.never()).save(any());
    }
}
