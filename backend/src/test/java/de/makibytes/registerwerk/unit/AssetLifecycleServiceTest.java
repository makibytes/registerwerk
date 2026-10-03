package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.internal.AssetLifecycleService;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.OnchainLevel;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.RedemptionReadinessPort;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetLifecycleService unit tests")
class AssetLifecycleServiceTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AssetBondTermsRepository bondTermsRepository;

    @Mock
    private AssetHolderRepository holderRepository;

    @Mock
    private RedemptionReadinessPort redemptionReadiness;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<de.makibytes.registerwerk.asset.api.RedemptionBlocker> redemptionBlockers;

    @Mock
    private de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository deploymentRepository;

    @InjectMocks
    private AssetLifecycleService assetLifecycleService;

    @org.junit.jupiter.api.BeforeEach
    void noBlockersByDefault() {
        org.mockito.Mockito.lenient().when(redemptionBlockers.orderedStream()).thenAnswer(i -> java.util.stream.Stream.empty());
    }

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

    // ── reactivate ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reactivate should transition a SUSPENDED asset back to ISSUED")
    void reactivate_shouldTransitionBackToIssued() {
        Asset asset = buildAsset(AssetStatus.SUSPENDED);
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.reactivate(asset.getId(), actorId);

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.ISSUED);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("reactivate should throw InvalidStateTransitionException when asset is not SUSPENDED")
    void reactivate_shouldThrowOnInvalidStatus() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> assetLifecycleService.reactivate(asset.getId(), UUID.randomUUID()))
            .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ── redeem ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("redeem moves an ISSUED asset to REDEMPTION_PENDING - REDEEMED only once every burn is final (C7)")
    void redeem_shouldTransitionToRedemptionPending() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        UUID actorId = UUID.randomUUID();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.redeem(asset.getId(), "eWpG §26", "RES-1", actorId, UUID.randomUUID());

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.REDEMPTION_PENDING);
        verify(eventPublisher).publishEvent(any(de.makibytes.registerwerk.asset.events.AssetRedeemedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(de.makibytes.registerwerk.asset.events.AssetRedemptionCompletedEvent.class));
    }

    @Test
    @DisplayName("C7: calling redeem again while REDEMPTION_PENDING resumes it: still pending, the event is republished so failed burns are re-driven")
    void redeem_resumesAPendingRedemption() {
        Asset asset = buildAsset(AssetStatus.REDEMPTION_PENDING);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assetLifecycleService.redeem(asset.getId(), "eWpG §26", "RES-1", UUID.randomUUID(), UUID.randomUUID());

        assertThat(asset.getStatus()).isEqualTo(AssetStatus.REDEMPTION_PENDING);
        verify(eventPublisher).publishEvent(any(de.makibytes.registerwerk.asset.events.AssetRedeemedEvent.class));
    }

    @Test
    @DisplayName("C5: redeem is refused on a deployment whose token does not count in whole units (burn amounts are raw base units)")
    void redeem_refusedOnFractionalDeployment() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        de.makibytes.registerwerk.deployment.api.AssetDeployment dep = new de.makibytes.registerwerk.deployment.api.AssetDeployment();
        dep.setId(UUID.randomUUID());
        dep.setDeploymentStatus(de.makibytes.registerwerk.deployment.api.AssetDeployment.DeploymentStatus.CONFIRMED);
        dep.setTokenDecimals(18);
        when(deploymentRepository.findByAssetId(asset.getId())).thenReturn(java.util.List.of(dep));

        assertThatThrownBy(() -> assetLifecycleService.redeem(asset.getId(), "eWpG §26", "RES-1", UUID.randomUUID(), null))
                .isInstanceOf(de.makibytes.registerwerk.shared.RegisterUnitsException.class)
                .hasMessageContaining("Asset redemption").hasMessageContaining("decimals=18");
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.ISSUED);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("C7: completeRedemption moves REDEMPTION_PENDING to REDEEMED (audited) and ignores every other status")
    void completeRedemption_onlyFromPending() {
        Asset pending = buildAsset(AssetStatus.REDEMPTION_PENDING);
        when(assetRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(assetLifecycleService.completeRedemption(pending.getId(), 2)).isTrue();
        assertThat(pending.getStatus()).isEqualTo(AssetStatus.REDEEMED);
        verify(eventPublisher).publishEvent(any(de.makibytes.registerwerk.asset.events.AssetRedemptionCompletedEvent.class));

        assertThat(assetLifecycleService.completeRedemption(pending.getId(), 2)).isFalse(); // already REDEEMED
        Asset issued = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(issued.getId())).thenReturn(Optional.of(issued));
        assertThat(assetLifecycleService.completeRedemption(issued.getId(), 0)).isFalse();
        assertThat(issued.getStatus()).isEqualTo(AssetStatus.ISSUED);
    }

    @Test
    @DisplayName("redeem reconciles AssetBondTerms.bondStatus to REDEEMED when bond terms exist")
    void redeem_reconcilesBondStatus() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        de.makibytes.registerwerk.deployment.api.AssetBondTerms terms =
                new de.makibytes.registerwerk.deployment.api.AssetBondTerms();
        terms.setAssetId(asset.getId());
        terms.setBondStatus(de.makibytes.registerwerk.deployment.api.BondStatus.ACTIVE);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bondTermsRepository.findById(asset.getId())).thenReturn(Optional.of(terms));
        when(redemptionReadiness.settledRetirementAction(asset.getId())).thenReturn(Optional.of(
                new RedemptionReadinessPort.SettledRetirement(UUID.randomUUID(), Set.of("0xaaa"))));

        assetLifecycleService.redeem(asset.getId(), "Final redemption", "CA-1", UUID.randomUUID(), UUID.randomUUID());

        assertThat(terms.getBondStatus()).isEqualTo(de.makibytes.registerwerk.deployment.api.BondStatus.REDEEMED);
        verify(bondTermsRepository).save(terms);
    }

    // ── redeem guards (review phase 3, T3-01) ─────────────────────────────────

    private de.makibytes.registerwerk.deployment.api.AssetBondTerms bondTerms(UUID assetId,
            de.makibytes.registerwerk.deployment.api.BondStatus status) {
        de.makibytes.registerwerk.deployment.api.AssetBondTerms terms =
                new de.makibytes.registerwerk.deployment.api.AssetBondTerms();
        terms.setAssetId(assetId);
        terms.setBondStatus(status);
        return terms;
    }

    @Test
    @DisplayName("redeemRefusedWithoutSettledRedemption: a bond without a settled REDEMPTION/CALL stays ISSUED")
    void redeemRefusedWithoutSettledRedemption() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(bondTermsRepository.findById(asset.getId())).thenReturn(Optional.of(
                bondTerms(asset.getId(), de.makibytes.registerwerk.deployment.api.BondStatus.ACTIVE)));
        when(redemptionReadiness.settledRetirementAction(asset.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetLifecycleService.redeem(asset.getId(), "eWpG §26", "REF", UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("settled REDEMPTION");
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.ISSUED);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a DEFAULTED bond is never silently overwritten with REDEEMED")
    void redeemRefusesDefaultedBondWithoutSettledRedemption() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        de.makibytes.registerwerk.deployment.api.AssetBondTerms terms =
                bondTerms(asset.getId(), de.makibytes.registerwerk.deployment.api.BondStatus.DEFAULTED);
        when(bondTermsRepository.findById(asset.getId())).thenReturn(Optional.of(terms));
        when(redemptionReadiness.settledRetirementAction(asset.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetLifecycleService.redeem(asset.getId(), "eWpG §26", "REF", UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEFAULTED");
        assertThat(terms.getBondStatus()).isEqualTo(de.makibytes.registerwerk.deployment.api.BondStatus.DEFAULTED);
    }

    @Test
    @DisplayName("redeemRefusedWhilePoolHoldsUnits: nominee pools must be unwound first")
    void redeemRefusedWhilePoolHoldsUnits() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        AssetHolder pool = new AssetHolder();
        pool.setHolderKind(HolderKind.NOMINEE_POOL);
        pool.setNominalAmount(new BigDecimal("100"));
        when(holderRepository.findActiveByAssetId(asset.getId())).thenReturn(List.of(pool));

        assertThatThrownBy(() -> assetLifecycleService.redeem(asset.getId(), "eWpG §26", "REF", UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nominee pool");
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.ISSUED);
    }

    @Test
    @DisplayName("redeem refused while a corporate action is in flight")
    void redeemRefusedWhileCorporateActionOpen() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(redemptionReadiness.hasOpenCorporateAction(asset.getId())).thenReturn(true);

        assertThatThrownBy(() -> assetLifecycleService.redeem(asset.getId(), "eWpG §26", "REF", UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corporate action");
    }

    @Test
    @DisplayName("redeem refused while a blocker (open repo trade) holds the asset as collateral")
    void redeemRefusedWhileRedemptionBlocked() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(redemptionBlockers.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(
                (de.makibytes.registerwerk.asset.api.RedemptionBlocker) id -> Optional.of("pledged in an open repo")));

        assertThatThrownBy(() -> assetLifecycleService.redeem(asset.getId(), "eWpG §26", "REF", UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pledged in an open repo");
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.ISSUED);
    }

    @Test
    @DisplayName("redeem requires a legal basis and reference")
    void redeemRequiresLegalBasis() {
        Asset asset = buildAsset(AssetStatus.ISSUED);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> assetLifecycleService.redeem(asset.getId(), " ", "REF", UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
