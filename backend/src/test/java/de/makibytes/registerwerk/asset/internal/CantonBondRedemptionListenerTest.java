package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.events.HolderRegisterChangedEvent;
import de.makibytes.registerwerk.blockchain.events.CantonBondRedeemedEvent;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies Phase 6 finding #1's other half: {@code CantonBondService.redeem} correctly
 * exercises {@code Redeem} on the DAML ledger and publishes {@link CantonBondRedeemedEvent},
 * but previously nothing consumed it — {@code AssetHolder.nominalAmount} stayed at its
 * pre-redemption value forever, so the register kept showing a retired bond as a live holding.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CantonBondRedemptionListener unit tests")
class CantonBondRedemptionListenerTest {

    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private AssetHolderRepository holderRepository;
    @Mock private ApplicationEventPublisher events;

    private CantonBondRedemptionListener listener;

    private final UUID deploymentId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new CantonBondRedemptionListener(deploymentRepository, holderRepository, events);
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        deployment.setAssetId(assetId);
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.of(deployment));
    }

    private static AssetHolder holder(BigDecimal nominal) {
        AssetHolder h = new AssetHolder();
        ReflectionTestUtils.setField(h, "id", UUID.randomUUID());
        h.setNominalAmount(nominal);
        return h;
    }

    @Test
    @DisplayName("zeroes every active holder's nominal amount and publishes a register-changed event per holder")
    void redemption_zeroesHolderBalances() {
        AssetHolder first = holder(new BigDecimal("1000"));
        AssetHolder second = holder(new BigDecimal("500"));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(first, second));
        UUID actorId = UUID.randomUUID();

        listener.onCantonBondRedeemed(new CantonBondRedeemedEvent(deploymentId, actorId, Instant.now()));

        assertThat(first.getNominalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(second.getNominalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(holderRepository).save(first);
        verify(holderRepository).save(second);

        ArgumentCaptor<HolderRegisterChangedEvent> captor = ArgumentCaptor.forClass(HolderRegisterChangedEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(HolderRegisterChangedEvent::actorId)
                .containsOnly(actorId);
    }

    @Test
    @DisplayName("a holder already at zero is skipped (no redundant save/event)")
    void zeroBalanceHolder_isSkipped() {
        AssetHolder alreadyZero = holder(BigDecimal.ZERO);
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(alreadyZero));

        listener.onCantonBondRedeemed(new CantonBondRedeemedEvent(deploymentId, UUID.randomUUID(), Instant.now()));

        verify(holderRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("no actorId on the event falls back to a SYSTEM-attributed change")
    void nullActor_fallsBackToSystem() {
        AssetHolder h = holder(new BigDecimal("100"));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(h));

        listener.onCantonBondRedeemed(new CantonBondRedeemedEvent(deploymentId, null, Instant.now()));

        ArgumentCaptor<HolderRegisterChangedEvent> captor = ArgumentCaptor.forClass(HolderRegisterChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().actorRole()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("deployment not found — does nothing, no exception")
    void deploymentNotFound_doesNothing() {
        when(deploymentRepository.findById(deploymentId)).thenReturn(Optional.empty());

        listener.onCantonBondRedeemed(new CantonBondRedeemedEvent(deploymentId, null, Instant.now()));

        verify(holderRepository, never()).findActiveByAssetId(any());
    }
}
