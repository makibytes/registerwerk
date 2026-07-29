package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrant;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reacts to {@link CustomerOffboardedEvent}: revokes every ASSET_TOKEN_ADMIN grant held by the
 * exiting entity (a departing customer must not retain forcedTransfer/forcedApprove/forceBurn
 * authority on anything) and flags its ISSUED assets — where it is the issuer — for the
 * operator's manual register-transfer-or-redemption decision, since that requires a business
 * choice (transfer to a named successor vs. redeem) this listener cannot make on its own.
 */
@Component
class CustomerOffboardingAssetListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerOffboardingAssetListener.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssetTokenAdminGrantRepository grantRepository;
    private final AssetRepository assetRepository;

    CustomerOffboardingAssetListener(AssetTokenAdminGrantRepository grantRepository, AssetRepository assetRepository) {
        this.grantRepository = grantRepository;
        this.assetRepository = assetRepository;
    }

    @ApplicationModuleListener
    void onCustomerOffboarded(CustomerOffboardedEvent event) {
        List<AssetTokenAdminGrant> activeGrants =
                grantRepository.findByEntityIdAndStatus(event.entityId(), AssetTokenAdminGrant.Status.ACTIVE);
        for (AssetTokenAdminGrant grant : activeGrants) {
            grant.setStatus(AssetTokenAdminGrant.Status.REVOKED);
            grant.setRevokedAt(Instant.now());
            grant.setRevokedBy(SYSTEM_ACTOR);
            grant.setRevokeReason("Customer offboarded: " + event.reason());
            grantRepository.save(grant);
        }
        if (!activeGrants.isEmpty()) {
            log.warn("Revoked {} ASSET_TOKEN_ADMIN grant(s) for offboarded entity={}",
                    activeGrants.size(), event.entityId());
        }

        List<Asset> issuedAssets = assetRepository.findByIssuerId(event.entityId()).stream()
                .filter(a -> a.getStatus() == AssetStatus.ISSUED || a.getStatus() == AssetStatus.SUSPENDED)
                .toList();
        if (!issuedAssets.isEmpty()) {
            log.warn("Entity {} offboarded while still issuer of {} live asset(s) — operator must "
                            + "decide register-transfer-to-successor vs. redemption for each: {}",
                    event.entityId(), issuedAssets.size(), issuedAssets.stream().map(Asset::getId).toList());
        }
    }
}
