package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.indexer.events.HolderBalanceSyncedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HolderDataService transfer-aggregation unit tests")
class HolderDataServiceTest {

    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private TokenTransferRepository tokenTransferRepository;
    @Mock private AssetHolderRepository assetHolderRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private HolderDataService service;

    private final UUID assetId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HolderDataService(deploymentRepository, tokenTransferRepository, assetHolderRepository, eventPublisher);
    }

    private void givenTransfers(TokenTransfer... transfers) {
        AssetDeployment deployment = new AssetDeployment();
        deployment.setId(deploymentId);
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment));
        when(tokenTransferRepository.findByDeploymentIdOrderByOccurredAtDesc(eq(deploymentId), any()))
                .thenReturn(new PageImpl<>(List.of(transfers)));
    }

    private TokenTransfer transfer(String from, String to, String amount, Instant at) {
        TokenTransfer t = new TokenTransfer();
        t.setFromAddress(from);
        t.setToAddress(to);
        t.setAmount(new BigDecimal(amount));
        t.setOccurredAt(at);
        return t;
    }

    @Test
    @DisplayName("mint + transfer chain nets out to per-wallet balances and creates holders")
    void aggregatesBalancesFromTransfers() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(
                transfer("0x0000000000000000000000000000000000000000", "0xAAA1", "1000", t0),
                transfer("0xAAA1", "0xBBB2", "300", t0.plusSeconds(60)),
                transfer("0xBBB2", "0x0000000000000000000000000000000000000000", "100", t0.plusSeconds(120)));
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        ArgumentCaptor<AssetHolder> saved = ArgumentCaptor.forClass(AssetHolder.class);
        verify(assetHolderRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(AssetHolder::getWalletAddress, h -> h.getNominalAmount().toPlainString())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("0xAAA1", "700"),
                        org.assertj.core.groups.Tuple.tuple("0xBBB2", "200"));
    }

    @Test
    @DisplayName("existing on-chain holder is updated to the indexed net balance; manual rows untouched")
    void updatesExistingHolderAndLeavesManualRowsAlone() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(
                transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "500", t0));

        AssetHolder onchainHolder = new AssetHolder();
        onchainHolder.setAssetId(assetId);
        onchainHolder.setWalletAddress("0xAAA1"); // different casing than the indexed event
        onchainHolder.setNominalAmount(new BigDecimal("100"));

        AssetHolder manualRow = new AssetHolder();
        manualRow.setAssetId(assetId);
        manualRow.setWalletAddress("0xMANUAL");
        manualRow.setNominalAmount(new BigDecimal("42"));

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(onchainHolder, manualRow)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        assertThat(onchainHolder.getNominalAmount()).isEqualByComparingTo("500");
        verify(assetHolderRepository, never()).save(manualRow);
    }

    @Test
    @DisplayName("no indexed transfers → nothing is written")
    void noTransfersNoWrites() {
        givenTransfers();

        service.syncHoldersFromBlockchain(assetId);

        verify(assetHolderRepository, never()).save(any());
    }

    @Test
    @DisplayName("a newly created on-chain holder publishes HolderBalanceSyncedEvent(newlyCreated=true)")
    void newHolderPublishesCreatedEvent() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xAAA1", "1000", t0));
        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        UUID savedId = UUID.randomUUID();
        when(assetHolderRepository.save(any())).thenAnswer(inv -> {
            AssetHolder h = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(h, "id", savedId);
            return h;
        });

        service.syncHoldersFromBlockchain(assetId);

        ArgumentCaptor<HolderBalanceSyncedEvent> captor = ArgumentCaptor.forClass(HolderBalanceSyncedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().holderId()).isEqualTo(savedId);
        assertThat(captor.getValue().newlyCreated()).isTrue();
    }

    @Test
    @DisplayName("an existing holder whose balance changed publishes HolderBalanceSyncedEvent(newlyCreated=false)")
    void updatedHolderPublishesUpdatedEvent() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "500", t0));

        AssetHolder onchainHolder = new AssetHolder();
        UUID holderId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(onchainHolder, "id", holderId);
        onchainHolder.setAssetId(assetId);
        onchainHolder.setWalletAddress("0xAAA1");
        onchainHolder.setNominalAmount(new BigDecimal("100"));

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(onchainHolder)));
        when(assetHolderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncHoldersFromBlockchain(assetId);

        ArgumentCaptor<HolderBalanceSyncedEvent> captor = ArgumentCaptor.forClass(HolderBalanceSyncedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().holderId()).isEqualTo(holderId);
        assertThat(captor.getValue().newlyCreated()).isFalse();
    }

    @Test
    @DisplayName("no-op sync (balance unchanged) does not publish an event")
    void noBalanceChangeDoesNotPublishEvent() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        givenTransfers(transfer("0x0000000000000000000000000000000000000000", "0xaaa1", "100", t0));

        AssetHolder onchainHolder = new AssetHolder();
        onchainHolder.setAssetId(assetId);
        onchainHolder.setWalletAddress("0xAAA1");
        onchainHolder.setNominalAmount(new BigDecimal("100"));

        when(assetHolderRepository.findByAssetId(eq(assetId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(onchainHolder)));

        service.syncHoldersFromBlockchain(assetId);

        verify(assetHolderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
