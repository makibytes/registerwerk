package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.internal.AssetDeploymentService;
import de.makibytes.registerwerk.asset.internal.AssetLifecycleService;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.asset.api.AssetRepository;
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
@DisplayName("AssetLifecycleService unit tests")
class AssetLifecycleServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AssetDeploymentService assetDeploymentService;

    @InjectMocks
    private AssetLifecycleService assetLifecycleService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Asset buildAsset(AssetStatus status) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setAssetNumber("AST-2026-000001");
        asset.setIssuerId(UUID.randomUUID());
        asset.setName("Test Bond");
        asset.setTokenStandard(TokenStandard.ERC20);
        asset.setOnchainLevel(OnchainLevel.NONE);
        asset.setStatus(status);
        return asset;
    }

    // ── submit ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submit should transition a DRAFT asset to PENDING_APPROVAL")
    void submit_shouldTransitionFromDraftToPendingApproval() {
        Asset asset = buildAsset(AssetStatus.DRAFT);
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.submit(asset.getId(), actorId);

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.PENDING_APPROVAL);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("submit should throw InvalidStateTransitionException when asset is already ISSUED")
    void submit_shouldThrowOnInvalidStatus() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> assetLifecycleService.submit(asset.getId(), UUID.randomUUID()))
            .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ── approve ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("approve should transition a PENDING_APPROVAL asset to APPROVED")
    void approve_shouldTransitionFromPendingToApproved() {
        Asset asset = buildAsset(AssetStatus.PENDING_APPROVAL);
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.approve(asset.getId(), actorId);

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.APPROVED);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    // ── reject ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reject should transition a PENDING_APPROVAL asset back to DRAFT")
    void reject_shouldTransitionBackToDraft() {
        Asset asset = buildAsset(AssetStatus.PENDING_APPROVAL);
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.reject(asset.getId(), "Incomplete documentation", actorId);

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.DRAFT);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    // ── issue ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue should transition an APPROVED asset to ISSUED")
    void issue_shouldTransitionToIssued() {
        Asset asset = buildAsset(AssetStatus.APPROVED);
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.issue(asset.getId(), actorId);

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.ISSUED);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    // ── suspend ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("suspend should transition an ISSUED asset to SUSPENDED")
    void suspend_shouldTransitionToSuspended() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.suspend(asset.getId(), actorId);

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.SUSPENDED);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    // ── redeem ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("redeem should transition an ISSUED asset to REDEEMED")
    void redeem_shouldTransitionToRedeemed() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.redeem(asset.getId(), actorId);

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.REDEEMED);
        verify(eventPublisher).publishEvent(any(Object.class));
    }
}
