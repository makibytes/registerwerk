package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrant;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.asset.events.AssetTokenAdminGrantedEvent;
import de.makibytes.registerwerk.asset.events.AssetTokenAdminRevokedEvent;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.orgidentity.api.PermissionGate;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages ASSET_TOKEN_ADMIN grants — the delegatable permission gating
 * forcedTransfer/forcedApprove/forceBurn beyond REGISTRY_ADMIN (see
 * {@code asset.web.AssetAccessChecker#canForceAdmin}). All create/revoke operations
 * require 4-eyes approval (via @RequiresStepUp on the controllers). Auto-expires grants
 * past their expires_at timestamp daily, mirroring {@code kyc.internal.SperrvermerkService}.
 */
@Service
@Transactional
public class AssetTokenAdminGrantService {

    private static final Logger log = LoggerFactory.getLogger(AssetTokenAdminGrantService.class);

    private final AssetTokenAdminGrantRepository repository;
    private final AssetRepository assetRepository;
    private final AssetHolderRepository assetHolderRepository;
    private final AssetDeploymentRepository assetDeploymentRepository;
    private final Erc3643Api erc3643Api;
    private final PermissionGate permissionGate;
    private final ApplicationEventPublisher events;

    public AssetTokenAdminGrantService(AssetTokenAdminGrantRepository repository,
                                AssetRepository assetRepository,
                                AssetHolderRepository assetHolderRepository,
                                AssetDeploymentRepository assetDeploymentRepository,
                                Erc3643Api erc3643Api,
                                PermissionGate permissionGate,
                                ApplicationEventPublisher events) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        this.assetHolderRepository = assetHolderRepository;
        this.assetDeploymentRepository = assetDeploymentRepository;
        this.erc3643Api = erc3643Api;
        this.permissionGate = permissionGate;
        this.events = events;
    }

    public AssetTokenAdminGrant grant(AssetTokenAdminGrant g, UUID createdBy, String actorRole, UUID dualControlApproverId) {
        g.setEligibilityBasis(validateWalletEligibility(g));
        g.setCreatedBy(createdBy);
        g.setDualControlApproverId(dualControlApproverId);
        g.setDualControlApprovedAt(Instant.now());
        g.setStatus(AssetTokenAdminGrant.Status.ACTIVE);
        AssetTokenAdminGrant saved = repository.save(g);
        log.warn("ASSET_TOKEN_ADMIN granted: id={} entity={} asset={} wallet={} basis={} legalBasis={}",
                saved.getId(), saved.getEntityId(), saved.getAssetId(), saved.getWalletAddress(),
                saved.getEligibilityBasis(), saved.getLegalBasis());
        events.publishEvent(new AssetTokenAdminGrantedEvent(saved.getId(), saved.getEntityId(), saved.getAssetId(),
                createdBy, actorRole, saved.getWalletAddress(), saved.getLegalBasis(),
                saved.getEligibilityBasis().name(), dualControlApproverId));
        return saved;
    }

    public AssetTokenAdminGrant revokeForAsset(UUID assetId, UUID grantId, UUID revokedBy, String actorRole,
                                               String reason, UUID dualControlApproverId) {
        AssetTokenAdminGrant grant = repository.findByIdAndAssetId(grantId, assetId)
                .orElseThrow(() -> new EntityNotFoundException("AssetTokenAdminGrant", grantId));
        return revoke(grant, revokedBy, actorRole, reason, dualControlApproverId);
    }

    public AssetTokenAdminGrant revokeEntityWide(UUID entityId, UUID grantId, UUID revokedBy, String actorRole,
                                                 String reason, UUID dualControlApproverId) {
        AssetTokenAdminGrant grant = repository.findByIdAndEntityIdAndAssetIdIsNull(grantId, entityId)
                .orElseThrow(() -> new EntityNotFoundException("AssetTokenAdminGrant", grantId));
        return revoke(grant, revokedBy, actorRole, reason, dualControlApproverId);
    }

    private AssetTokenAdminGrant revoke(AssetTokenAdminGrant g, UUID revokedBy, String actorRole, String reason,
                                        UUID dualControlApproverId) {
        UUID grantId = g.getId();
        if (g.getStatus() != AssetTokenAdminGrant.Status.ACTIVE) {
            throw new IllegalStateException("Cannot revoke grant " + grantId + " — status is " + g.getStatus());
        }
        g.setStatus(AssetTokenAdminGrant.Status.REVOKED);
        g.setRevokedAt(Instant.now());
        g.setRevokedBy(revokedBy);
        g.setRevokeReason(reason);
        if (dualControlApproverId != null) {
            g.setRevokeDualControlApproverId(dualControlApproverId);
            g.setRevokeDualControlApprovedAt(Instant.now());
        }
        AssetTokenAdminGrant saved = repository.save(g);
        log.warn("ASSET_TOKEN_ADMIN revoked: id={} by={} reason={}", grantId, revokedBy, reason);
        events.publishEvent(new AssetTokenAdminRevokedEvent(grantId, saved.getEntityId(), saved.getAssetId(),
                revokedBy, actorRole, reason, dualControlApproverId));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AssetTokenAdminGrant> findByAsset(UUID assetId) {
        return repository.findByAssetIdAndStatus(assetId, AssetTokenAdminGrant.Status.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<AssetTokenAdminGrant> findEntityWideByEntity(UUID entityId) {
        return repository.findByEntityIdAndAssetIdIsNullAndStatus(entityId, AssetTokenAdminGrant.Status.ACTIVE);
    }

    /** Daily job auto-expires grants past their expires_at. */
    @SchedulerLock(name = "assetTokenAdminGrantAutoExpire", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 5 3 * * *")
    public void autoExpire() {
        List<AssetTokenAdminGrant> expired = repository.findExpiredActive(Instant.now());
        for (AssetTokenAdminGrant g : expired) {
            g.setStatus(AssetTokenAdminGrant.Status.EXPIRED);
            g.setRevokedAt(Instant.now());
            g.setRevokeReason("AUTO_EXPIRED");
            repository.save(g);
            log.info("ASSET_TOKEN_ADMIN grant auto-expired: id={} entity={}", g.getId(), g.getEntityId());
        }
        if (!expired.isEmpty()) {
            log.info("Auto-expired {} ASSET_TOKEN_ADMIN grant(s).", expired.size());
        }
    }

    /**
     * Creation-time eligibility check — three-way branch depending on whether the grant is
     * entity-wide, or asset-scoped to the asset's issuer vs. one of its holders. Throws
     * {@link IllegalStateException} on failure; returns the basis that passed (recorded on
     * the grant for audit — never re-checked live on the runtime hot path).
     */
    private AssetTokenAdminGrant.EligibilityBasis validateWalletEligibility(AssetTokenAdminGrant g) {
        if (g.getAssetId() == null) {
            requireChainConfigId(g, "entity-wide");
            requireWalletBoundToEntity(g);
            return AssetTokenAdminGrant.EligibilityBasis.ENTITY_WALLET_BINDING;
        }

        Asset asset = assetRepository.findById(g.getAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Asset", g.getAssetId()));
        boolean isIssuer = g.getEntityId().equals(asset.getIssuerId());
        // Active-only: a removed holder no longer holds the position this eligibility basis relies on.
        boolean isHolder = assetHolderRepository.existsActiveByAssetIdAndInvestorId(g.getAssetId(), g.getEntityId());
        if (!isIssuer && !isHolder) {
            throw new IllegalStateException(
                    "Entity " + g.getEntityId() + " is neither the issuer nor a holder of asset " + g.getAssetId());
        }

        if (isIssuer) {
            requireChainConfigId(g, "issuer");
            requireWalletBoundToEntity(g);
            return AssetTokenAdminGrant.EligibilityBasis.ISSUER_WALLET_BINDING;
        }

        AssetHolder holder = assetHolderRepository.findActiveByAssetIdAndWalletAddress(g.getAssetId(), g.getWalletAddress())
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet " + g.getWalletAddress() + " is not a registered holder wallet for asset " + g.getAssetId()));
        if (!g.getEntityId().equals(holder.getInvestorId())) {
            throw new IllegalStateException("Wallet " + g.getWalletAddress() + " does not belong to entity " + g.getEntityId());
        }
        if (!Boolean.TRUE.equals(holder.getWhitelisted())) {
            throw new IllegalStateException("Wallet " + g.getWalletAddress() + " is not whitelisted for asset " + g.getAssetId());
        }

        if (asset.getTokenStandard() == TokenStandard.ERC3643 || asset.getTokenStandard() == TokenStandard.CONF_ERC3643) {
            UUID suiteId = resolveSuiteId(g.getAssetId());
            if (!erc3643Api.isWalletVerified(suiteId, g.getWalletAddress())) {
                throw new IllegalStateException(
                        "Wallet " + g.getWalletAddress() + " is not KYC-verified in the T-REX IdentityRegistry for asset " + g.getAssetId());
            }
            return AssetTokenAdminGrant.EligibilityBasis.INVESTOR_WHITELIST_AND_ONCHAINID;
        }
        return AssetTokenAdminGrant.EligibilityBasis.INVESTOR_WHITELIST;
    }

    private void requireChainConfigId(AssetTokenAdminGrant g, String grantKind) {
        if (g.getChainConfigId() == null) {
            throw new IllegalStateException("chainConfigId is required for a " + grantKind + " grant");
        }
    }

    private void requireWalletBoundToEntity(AssetTokenAdminGrant g) {
        if (!permissionGate.isWalletBoundToEntity(g.getWalletAddress(), g.getEntityId(), g.getChainConfigId())) {
            throw new IllegalStateException(
                    "Wallet " + g.getWalletAddress() + " is not a confirmed, bound wallet of entity " + g.getEntityId());
        }
    }

    private UUID resolveSuiteId(UUID assetId) {
        for (AssetDeployment dep : assetDeploymentRepository.findByAssetId(assetId)) {
            Optional<Erc3643Suite> suite = erc3643Api.findSuiteByDeployment(dep.getId());
            if (suite.isPresent()) {
                return suite.get().getId();
            }
        }
        throw new IllegalStateException("No ERC-3643 suite found for asset " + assetId);
    }
}
