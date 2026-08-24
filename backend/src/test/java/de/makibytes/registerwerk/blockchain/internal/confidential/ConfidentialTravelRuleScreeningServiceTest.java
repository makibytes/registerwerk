package de.makibytes.registerwerk.blockchain.internal.confidential;

import de.makibytes.registerwerk.blockchain.api.ZamaRelayerClient;
import de.makibytes.registerwerk.blockchain.events.ConfidentialTransferScreenedEvent;
import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialTokenEventReader.ConfidentialTokenEvent;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.travelrule.api.TravelRuleGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the cursor retry/escalation logic:
 * {@code screenDeployment} advanced its sync cursor past every event in a batch unconditionally,
 * even when a decrypt failed — silently and permanently skipping Travel Rule screening for that
 * transfer. These tests verify the bounded-retry replacement: a failed decrypt holds the cursor
 * back (so the event is retried next run) up to {@code MAX_RETRY_ATTEMPTS}, after which the
 * cursor advances anyway rather than risking a permanent wedge.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfidentialTravelRuleScreeningService — cursor retry/escalation")
class ConfidentialTravelRuleScreeningServiceTest {

    @Mock private AssetLookupPort assetLookupPort;
    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private ConfidentialTransferScreeningStateRepository stateRepository;
    @Mock private ConfidentialTokenEventReader eventReader;
    @Mock private ZamaRelayerClient zamaRelayerClient;
    @Mock private TravelRuleGate travelRuleGate;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ConfidentialTravelRuleScreeningService service;

    private static final UUID ASSET_ID = UUID.randomUUID();
    private static final UUID DEPLOYMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConfidentialTravelRuleScreeningService(
                assetLookupPort, deploymentRepository, chainConfigRepository, stateRepository,
                eventReader, zamaRelayerClient, travelRuleGate, eventPublisher);
    }

    private AssetLookupPort.AssetInfo asset() {
        return new AssetLookupPort.AssetInfo(
                ASSET_ID, "Confidential Asset", null, TokenStandard.CONF_ERC20, null, null, null, "AST-100", null);
    }

    private AssetDeployment deployment() {
        AssetDeployment dep = new AssetDeployment();
        dep.setId(DEPLOYMENT_ID);
        dep.setAssetId(ASSET_ID);
        dep.setChain(Chain.ETHEREUM);
        dep.setNetwork(Network.TESTNET);
        dep.setContractAddress("0x" + "a".repeat(40));
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        return dep;
    }

    private ChainConfig chainConfig() {
        return new ChainConfig();
    }

    private ConfidentialTransferScreeningState freshState() {
        ConfidentialTransferScreeningState state = new ConfidentialTransferScreeningState();
        state.setAssetDeploymentId(DEPLOYMENT_ID);
        return state;
    }

    private ConfidentialTokenEvent transferEvent(long blockNumber) {
        return new ConfidentialTokenEvent(
                "TRANSFER", "0x" + "1".repeat(40), "0x" + "2".repeat(40),
                BigInteger.valueOf(999), blockNumber, "0xtx" + blockNumber, 0);
    }

    @Test
    @DisplayName("all events decrypt successfully: cursor advances to the highest block, failure streak resets")
    void allResolved_cursorAdvances_failureStreakResets() {
        when(chainConfigRepository.findByIdentifier(anyString())).thenReturn(Optional.of(chainConfig()));
        ConfidentialTransferScreeningState state = freshState();
        state.setConsecutiveDecryptFailures(2); // simulate a prior partial-failure streak
        when(stateRepository.findByAssetDeploymentId(DEPLOYMENT_ID)).thenReturn(Optional.of(state));
        when(eventReader.eventsSince(any(), anyString(), eq(0L)))
                .thenReturn(List.of(transferEvent(10), transferEvent(20)));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), anyString()))
                .thenReturn(BigInteger.valueOf(999_000_000));

        service.screenDeployment(asset(), deployment());

        assertThat(state.getLastScreenedBlock()).isEqualTo(20L);
        assertThat(state.getConsecutiveDecryptFailures()).isZero();
        assertThat(state.getLastError()).isNull();
        verify(eventPublisher, times(2)).publishEvent(any(ConfidentialTransferScreenedEvent.class));
    }

    @Test
    @DisplayName("a failed decrypt holds the cursor back (below MAX_RETRY_ATTEMPTS) so the event is retried next run")
    void oneFailure_belowThreshold_cursorHeldBack() {
        when(chainConfigRepository.findByIdentifier(anyString())).thenReturn(Optional.of(chainConfig()));
        ConfidentialTransferScreeningState state = freshState();
        when(stateRepository.findByAssetDeploymentId(DEPLOYMENT_ID)).thenReturn(Optional.of(state));
        when(eventReader.eventsSince(any(), anyString(), eq(0L)))
                .thenReturn(List.of(transferEvent(10), transferEvent(20)));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), anyString()))
                .thenThrow(new RuntimeException("KMS unreachable"));

        service.screenDeployment(asset(), deployment());

        // neither event resolved -> cursor stays at the pre-batch value (0), not the batch's
        // highest block, so both events are re-fetched (eventsSince(..., 0L)) on the next run
        assertThat(state.getLastScreenedBlock()).isZero();
        assertThat(state.getConsecutiveDecryptFailures()).isEqualTo(1);
        verify(eventPublisher, times(2)).publishEvent(any(ConfidentialTransferScreenedEvent.class));
        verify(travelRuleGate, never()).enforceOutbound(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a decrypt failure that persists MAX_RETRY_ATTEMPTS runs: cursor advances anyway and the streak resets")
    void failurePersists_givesUpAfterMaxAttempts_cursorAdvances() {
        when(chainConfigRepository.findByIdentifier(anyString())).thenReturn(Optional.of(chainConfig()));
        ConfidentialTransferScreeningState state = freshState();
        state.setConsecutiveDecryptFailures(4); // one shy of MAX_RETRY_ATTEMPTS (5)
        when(stateRepository.findByAssetDeploymentId(DEPLOYMENT_ID)).thenReturn(Optional.of(state));
        when(eventReader.eventsSince(any(), anyString(), eq(0L)))
                .thenReturn(List.of(transferEvent(10)));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), anyString()))
                .thenThrow(new RuntimeException("still unreachable"));

        service.screenDeployment(asset(), deployment());

        // 5th consecutive failure -> gives up, advances past the stuck event, resets the streak
        assertThat(state.getLastScreenedBlock()).isEqualTo(10L);
        assertThat(state.getConsecutiveDecryptFailures()).isZero();
    }

    @Test
    @DisplayName("a partial batch (first event fails, second succeeds) only credits the cursor up to the failure")
    void partialBatch_onlyCreditsUpToFirstFailure() {
        when(chainConfigRepository.findByIdentifier(anyString())).thenReturn(Optional.of(chainConfig()));
        ConfidentialTransferScreeningState state = freshState();
        when(stateRepository.findByAssetDeploymentId(DEPLOYMENT_ID)).thenReturn(Optional.of(state));
        when(eventReader.eventsSince(any(), anyString(), eq(0L)))
                .thenReturn(List.of(transferEvent(10), transferEvent(20)));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), anyString()))
                .thenThrow(new RuntimeException("decrypt failed for block 10"))
                .thenReturn(BigInteger.valueOf(999_000_000)); // block 20 succeeds

        service.screenDeployment(asset(), deployment());

        // block 20 succeeding after block 10 failed must NOT advance the cursor past block 10 —
        // otherwise block 10's transfer would never be retried and Travel Rule screening for it
        // would be silently and permanently skipped (the original bug).
        assertThat(state.getLastScreenedBlock()).isZero();
        assertThat(state.getConsecutiveDecryptFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("eventsSince failing does not advance the cursor and records lastError")
    void eventsSinceThrows_cursorUnchanged_lastErrorRecorded() {
        when(chainConfigRepository.findByIdentifier(anyString())).thenReturn(Optional.of(chainConfig()));
        ConfidentialTransferScreeningState state = freshState();
        state.setLastScreenedBlock(5L);
        when(stateRepository.findByAssetDeploymentId(DEPLOYMENT_ID)).thenReturn(Optional.of(state));
        when(eventReader.eventsSince(any(), anyString(), eq(5L)))
                .thenThrow(new ConfidentialTokenEventReader.ConfidentialEventQueryException("graph node down"));

        service.screenDeployment(asset(), deployment());

        assertThat(state.getLastScreenedBlock()).isEqualTo(5L);
        assertThat(state.getLastError()).contains("graph node down");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("TRANSFER events with a successful decrypt are still evaluated through TravelRuleGate")
    void successfulTransfer_stillEnforcesTravelRule() {
        when(chainConfigRepository.findByIdentifier(anyString())).thenReturn(Optional.of(chainConfig()));
        ConfidentialTransferScreeningState state = freshState();
        when(stateRepository.findByAssetDeploymentId(DEPLOYMENT_ID)).thenReturn(Optional.of(state));
        ConfidentialTokenEvent event = transferEvent(10);
        when(eventReader.eventsSince(any(), anyString(), eq(0L))).thenReturn(List.of(event));
        when(zamaRelayerClient.requestOperatorDecrypt(anyString(), anyString()))
                .thenReturn(BigInteger.valueOf(999_000_000));

        service.screenDeployment(asset(), deployment());

        ArgumentCaptor<java.math.BigDecimal> amountCaptor = ArgumentCaptor.forClass(java.math.BigDecimal.class);
        verify(travelRuleGate).enforceOutbound(
                eq(ASSET_ID), eq(event.from()), eq(event.to()), isNull(), amountCaptor.capture(), anyString());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("999");
    }
}
