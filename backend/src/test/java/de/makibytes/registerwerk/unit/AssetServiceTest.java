package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.application.asset.AssetService;
import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.customer.EntityNumberGenerator;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.enums.AssetStatus;
import de.makibytes.registerwerk.domain.enums.Jurisdiction;
import de.makibytes.registerwerk.domain.enums.OnchainLevel;
import de.makibytes.registerwerk.domain.enums.TokenStandard;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AssetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetService unit tests")
class AssetServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private EntityNumberGenerator entityNumberGenerator;

    @InjectMocks
    private AssetService assetService;

    private Asset buildAsset() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setIssuerId(UUID.randomUUID());
        asset.setName("Test Bond");
        asset.setTokenStandard(TokenStandard.ERC20);
        asset.setOnchainLevel(OnchainLevel.NONE);
        asset.setStatus(AssetStatus.DRAFT);
        return asset;
    }

    // ── createAsset ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("createAsset should set DRAFT status and assign an asset number before saving")
    void createAsset_shouldSetDraftStatusAndAssetNumber() {
        Asset asset = buildAsset();
        UUID actorId = UUID.randomUUID();
        when(entityNumberGenerator.generateAssetNumber()).thenReturn("AST-2026-000001");
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Asset result = assetService.createAsset(asset, actorId);

        assertThat(result.getStatus()).isEqualTo(AssetStatus.DRAFT);
        assertThat(result.getAssetNumber()).isEqualTo("AST-2026-000001");
        verify(entityNumberGenerator).generateAssetNumber();
    }

    @Test
    @DisplayName("createAsset should publish an ASSET_CREATED audit event with actorId")
    void createAsset_shouldPublishAuditEvent() {
        Asset asset = buildAsset();
        UUID actorId = UUID.randomUUID();
        when(entityNumberGenerator.generateAssetNumber()).thenReturn("AST-2026-000002");
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetService.createAsset(asset, actorId);

        verify(auditEventPublisher).publish(
            eq("ASSET_CREATED"), eq("Asset"), any(UUID.class), eq(actorId), any(), any());
    }

    // ── getAsset ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getAsset should throw EntityNotFoundException when the ID does not exist")
    void getAsset_shouldThrowWhenNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(assetRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetService.getAsset(unknownId))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining(unknownId.toString());
    }

    // ── updateAsset ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateAsset should apply non-null patch fields only")
    void updateAsset_shouldApplyNonNullPatchFields() {
        Asset existing = buildAsset();
        existing.setName("Original Name");
        existing.setIsin("DE000A0001");
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Asset patch = new Asset();
        patch.setName("Updated Name");

        Asset result = assetService.updateAsset(existing.getId(), patch, actorId);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getIsin()).isEqualTo("DE000A0001");
    }

    @Test
    @DisplayName("updateAsset should publish an ASSET_UPDATED audit event with the given actorId")
    void updateAsset_shouldPublishAuditEvent() {
        Asset existing = buildAsset();
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetService.updateAsset(existing.getId(), new Asset(), actorId);

        verify(auditEventPublisher).publish(
            eq("ASSET_UPDATED"), eq("Asset"), eq(existing.getId()), eq(actorId), any(), any());
    }

    @Test
    @DisplayName("updateAsset should update jurisdiction when provided in the patch")
    void updateAsset_shouldUpdateJurisdiction() {
        Asset existing = buildAsset();
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Asset patch = new Asset();
        patch.setJurisdiction(Jurisdiction.DE_EWPG);

        Asset result = assetService.updateAsset(existing.getId(), patch, actorId);

        assertThat(result.getJurisdiction()).isEqualTo(Jurisdiction.DE_EWPG);
    }
}
