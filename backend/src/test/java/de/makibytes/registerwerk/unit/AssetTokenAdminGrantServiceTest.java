package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrant;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.asset.internal.AssetTokenAdminGrantService;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.orgidentity.api.PermissionGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssetTokenAdminGrantServiceTest {

    AssetTokenAdminGrantRepository repository;
    AssetRepository assetRepository;
    AssetHolderRepository assetHolderRepository;
    AssetDeploymentRepository assetDeploymentRepository;
    Erc3643Api erc3643Api;
    PermissionGate permissionGate;
    ApplicationEventPublisher events;
    AssetTokenAdminGrantService service;

    static final UUID ENTITY = UUID.randomUUID();
    static final UUID OTHER_ENTITY = UUID.randomUUID();
    static final UUID ASSET_ID = UUID.randomUUID();
    static final UUID CHAIN_CONFIG_ID = UUID.randomUUID();
    static final UUID ACTOR = UUID.randomUUID();
    static final UUID APPROVER = UUID.randomUUID();
    static final String WALLET = "0xabc";

    @BeforeEach
    void setUp() {
        repository = mock(AssetTokenAdminGrantRepository.class);
        assetRepository = mock(AssetRepository.class);
        assetHolderRepository = mock(AssetHolderRepository.class);
        assetDeploymentRepository = mock(AssetDeploymentRepository.class);
        erc3643Api = mock(Erc3643Api.class);
        permissionGate = mock(PermissionGate.class);
        events = mock(ApplicationEventPublisher.class);
        service = new AssetTokenAdminGrantService(repository, assetRepository, assetHolderRepository,
                assetDeploymentRepository, erc3643Api, permissionGate, events);
        when(repository.save(any(AssetTokenAdminGrant.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AssetTokenAdminGrant grantRequest(UUID assetId, UUID entityId) {
        AssetTokenAdminGrant g = new AssetTokenAdminGrant();
        g.setAssetId(assetId);
        g.setEntityId(entityId);
        g.setWalletAddress(WALLET);
        g.setChainConfigId(CHAIN_CONFIG_ID);
        g.setLegalBasis("BaFin Az. 2026-001");
        return g;
    }

    private Asset assetWithIssuer(UUID issuerId, TokenStandard standard) {
        Asset a = new Asset();
        a.setIssuerId(issuerId);
        a.setTokenStandard(standard);
        return a;
    }

    // ── Entity-wide grants ────────────────────────────────────────────────────

    @Test
    void grant_entityWide_requiresChainConfigId() {
        AssetTokenAdminGrant g = grantRequest(null, ENTITY);
        g.setChainConfigId(null);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chainConfigId");
    }

    @Test
    void grant_entityWide_requiresWalletBoundToEntity() {
        AssetTokenAdminGrant g = grantRequest(null, ENTITY);
        when(permissionGate.isWalletBoundToEntity(WALLET, ENTITY, CHAIN_CONFIG_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bound");
    }

    @Test
    void grant_entityWide_walletBound_succeeds() {
        AssetTokenAdminGrant g = grantRequest(null, ENTITY);
        when(permissionGate.isWalletBoundToEntity(WALLET, ENTITY, CHAIN_CONFIG_ID)).thenReturn(true);

        AssetTokenAdminGrant saved = service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER);

        assertThat(saved.getStatus()).isEqualTo(AssetTokenAdminGrant.Status.ACTIVE);
        assertThat(saved.getEligibilityBasis()).isEqualTo(AssetTokenAdminGrant.EligibilityBasis.ENTITY_WALLET_BINDING);
        assertThat(saved.getCreatedBy()).isEqualTo(ACTOR);
        assertThat(saved.getDualControlApproverId()).isEqualTo(APPROVER);
        verify(events).publishEvent(any(de.makibytes.registerwerk.asset.events.AssetTokenAdminGrantedEvent.class));
    }

    // ── Asset-scoped grants: issuer ────────────────────────────────────────────

    @Test
    void grant_assetScoped_issuer_requiresChainConfigId() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(assetWithIssuer(ENTITY, TokenStandard.ERC20)));
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);
        g.setChainConfigId(null);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chainConfigId");
    }

    @Test
    void grant_assetScoped_issuer_requiresWalletBoundToEntity() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(assetWithIssuer(ENTITY, TokenStandard.ERC20)));
        when(permissionGate.isWalletBoundToEntity(WALLET, ENTITY, CHAIN_CONFIG_ID)).thenReturn(false);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void grant_assetScoped_issuer_walletBound_succeeds() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(assetWithIssuer(ENTITY, TokenStandard.ERC20)));
        when(permissionGate.isWalletBoundToEntity(WALLET, ENTITY, CHAIN_CONFIG_ID)).thenReturn(true);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        AssetTokenAdminGrant saved = service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER);

        assertThat(saved.getEligibilityBasis()).isEqualTo(AssetTokenAdminGrant.EligibilityBasis.ISSUER_WALLET_BINDING);
    }

    // ── Asset-scoped grants: neither issuer nor holder ─────────────────────────

    @Test
    void grant_assetScoped_neitherIssuerNorHolder_rejected() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(assetWithIssuer(OTHER_ENTITY, TokenStandard.ERC20)));
        when(assetHolderRepository.existsActiveByAssetIdAndInvestorId(ASSET_ID, ENTITY)).thenReturn(false);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neither the issuer nor a holder");
    }

    // ── Asset-scoped grants: investor/holder ───────────────────────────────────

    private void stubAsHolder(TokenStandard standard, boolean walletFound, boolean belongsToEntity, boolean whitelisted) {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(assetWithIssuer(OTHER_ENTITY, standard)));
        when(assetHolderRepository.existsActiveByAssetIdAndInvestorId(ASSET_ID, ENTITY)).thenReturn(true);
        if (!walletFound) {
            when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(ASSET_ID, WALLET)).thenReturn(Optional.empty());
            return;
        }
        AssetHolder holder = new AssetHolder();
        holder.setInvestorId(belongsToEntity ? ENTITY : OTHER_ENTITY);
        holder.setWhitelisted(whitelisted);
        when(assetHolderRepository.findActiveByAssetIdAndWalletAddress(ASSET_ID, WALLET)).thenReturn(Optional.of(holder));
    }

    @Test
    void grant_assetScoped_holder_walletNotRegistered_rejected() {
        stubAsHolder(TokenStandard.ERC20, false, true, true);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a registered holder wallet");
    }

    @Test
    void grant_assetScoped_holder_walletBelongsToDifferentEntity_rejected() {
        stubAsHolder(TokenStandard.ERC20, true, false, true);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to entity");
    }

    @Test
    void grant_assetScoped_holder_walletNotWhitelisted_rejected() {
        stubAsHolder(TokenStandard.ERC20, true, true, false);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not whitelisted");
    }

    @Test
    void grant_assetScoped_holder_nonErc3643_whitelisted_succeeds() {
        stubAsHolder(TokenStandard.ERC20, true, true, true);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        AssetTokenAdminGrant saved = service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER);

        assertThat(saved.getEligibilityBasis()).isEqualTo(AssetTokenAdminGrant.EligibilityBasis.INVESTOR_WHITELIST);
        verifyNoInteractions(erc3643Api);
    }

    @Test
    void grant_assetScoped_holder_erc3643_notOnchainVerified_rejected() {
        stubAsHolder(TokenStandard.ERC3643, true, true, true);
        AssetDeployment dep = new AssetDeployment();
        dep.setId(UUID.randomUUID());
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(dep));
        Erc3643Suite suite = mock(Erc3643Suite.class);
        UUID suiteId = UUID.randomUUID();
        when(suite.getId()).thenReturn(suiteId);
        when(erc3643Api.findSuiteByDeployment(dep.getId())).thenReturn(Optional.of(suite));
        when(erc3643Api.isWalletVerified(suiteId, WALLET)).thenReturn(false);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        assertThatThrownBy(() -> service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not KYC-verified");
    }

    @Test
    void grant_assetScoped_holder_erc3643_verified_succeeds() {
        stubAsHolder(TokenStandard.ERC3643, true, true, true);
        AssetDeployment dep = new AssetDeployment();
        dep.setId(UUID.randomUUID());
        when(assetDeploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(dep));
        Erc3643Suite suite = mock(Erc3643Suite.class);
        UUID suiteId = UUID.randomUUID();
        when(suite.getId()).thenReturn(suiteId);
        when(erc3643Api.findSuiteByDeployment(dep.getId())).thenReturn(Optional.of(suite));
        when(erc3643Api.isWalletVerified(suiteId, WALLET)).thenReturn(true);
        AssetTokenAdminGrant g = grantRequest(ASSET_ID, ENTITY);

        AssetTokenAdminGrant saved = service.grant(g, ACTOR, "REGISTRY_ADMIN", APPROVER);

        assertThat(saved.getEligibilityBasis()).isEqualTo(AssetTokenAdminGrant.EligibilityBasis.INVESTOR_WHITELIST_AND_ONCHAINID);
    }

    // ── Revoke / auto-expire ────────────────────────────────────────────────────

    @Test
    void revoke_activeGrant_succeeds() {
        AssetTokenAdminGrant existing = grantRequest(ASSET_ID, ENTITY);
        existing.setStatus(AssetTokenAdminGrant.Status.ACTIVE);
        UUID grantId = UUID.randomUUID();
        when(repository.findById(grantId)).thenReturn(Optional.of(existing));

        AssetTokenAdminGrant revoked = service.revoke(grantId, ACTOR, "REGISTRY_ADMIN", "no longer needed", APPROVER);

        assertThat(revoked.getStatus()).isEqualTo(AssetTokenAdminGrant.Status.REVOKED);
        assertThat(revoked.getRevokedBy()).isEqualTo(ACTOR);
        verify(events).publishEvent(any(de.makibytes.registerwerk.asset.events.AssetTokenAdminRevokedEvent.class));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("revoke persists the second approver on the entity and publishes it on the event " +
            "(previously silently discarded both places)")
    void revoke_persistsAndPublishesApprover() {
        AssetTokenAdminGrant existing = grantRequest(ASSET_ID, ENTITY);
        existing.setStatus(AssetTokenAdminGrant.Status.ACTIVE);
        UUID grantId = UUID.randomUUID();
        when(repository.findById(grantId)).thenReturn(Optional.of(existing));

        AssetTokenAdminGrant revoked = service.revoke(grantId, ACTOR, "REGISTRY_ADMIN", "no longer needed", APPROVER);

        assertThat(revoked.getRevokeDualControlApproverId()).isEqualTo(APPROVER);
        assertThat(revoked.getRevokeDualControlApprovedAt()).isNotNull();
        org.mockito.ArgumentCaptor<de.makibytes.registerwerk.asset.events.AssetTokenAdminRevokedEvent> captor =
                org.mockito.ArgumentCaptor.forClass(de.makibytes.registerwerk.asset.events.AssetTokenAdminRevokedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(APPROVER);
    }

    @Test
    void revoke_alreadyRevoked_throws() {
        AssetTokenAdminGrant existing = grantRequest(ASSET_ID, ENTITY);
        existing.setStatus(AssetTokenAdminGrant.Status.REVOKED);
        UUID grantId = UUID.randomUUID();
        when(repository.findById(grantId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.revoke(grantId, ACTOR, "REGISTRY_ADMIN", "x", APPROVER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void autoExpire_marksExpiredGrantsAsExpired() {
        AssetTokenAdminGrant expiring = grantRequest(ASSET_ID, ENTITY);
        expiring.setExpiresAt(Instant.now().minusSeconds(60));
        when(repository.findExpiredActive(any())).thenReturn(List.of(expiring));

        service.autoExpire();

        assertThat(expiring.getStatus()).isEqualTo(AssetTokenAdminGrant.Status.EXPIRED);
        assertThat(expiring.getRevokeReason()).isEqualTo("AUTO_EXPIRED");
        verify(repository).save(expiring);
    }
}
