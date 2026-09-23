package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.indexer.events.NomineePoolHolderRegisteredEvent;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NomineePoolHolderService (T2-18)")
class NomineePoolHolderServiceTest {

    @Mock private AssetHolderRepository holderRepository;
    @Mock private AssetLookupPort assetLookupPort;
    @Mock private LendingMarketRepository lendingMarketRepository;
    @Mock private ApplicationEventPublisher events;

    private final UUID assetId = UUID.randomUUID();
    private final UUID operatorEntity = UUID.randomUUID();

    private NomineePoolHolderService service(String configuredEntity) {
        return new NomineePoolHolderService(holderRepository, assetLookupPort, lendingMarketRepository, events,
                configuredEntity);
    }

    private void givenAssetWithHolders(AssetHolder... holders) {
        when(assetLookupPort.findById(assetId)).thenReturn(Optional.of(new AssetLookupPort.AssetInfo(assetId, "n", null, null, null, null, null, "A", "ISSUED")));
        when(holderRepository.findByAssetId(eq(assetId), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(holders)));
    }

    @Test
    @DisplayName("registers a chain-derived NOMINEE_POOL row in the configured operator entity and audits it")
    void registersPoolRow() {
        givenAssetWithHolders();
        when(holderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssetHolder row = service(operatorEntity.toString())
                .register(assetId, "0xMarket", "LENDING_MARKET", null, null, "SYSTEM");

        assertThat(row.getHolderKind()).isEqualTo(HolderKind.NOMINEE_POOL);
        assertThat(row.getInvestorId()).isEqualTo(operatorEntity);
        assertThat(row.isChainDerived()).isTrue();
        ArgumentCaptor<NomineePoolHolderRegisteredEvent> captor = ArgumentCaptor.forClass(NomineePoolHolderRegisteredEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().poolKind()).isEqualTo("LENDING_MARKET");
    }

    @Test
    @DisplayName("idempotent for an existing pool row (case-insensitive address)")
    void idempotentForExistingPool() {
        AssetHolder existing = new AssetHolder();
        existing.setWalletAddress("0xMARKET");
        existing.setHolderKind(HolderKind.NOMINEE_POOL);
        givenAssetWithHolders(existing);

        assertThat(service(operatorEntity.toString())
                .register(assetId, "0xmarket", "LENDING_MARKET", null, null, "SYSTEM")).isSameAs(existing);
        verify(holderRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("refuses to turn an investor's register entry into a pool")
    void refusesInvestorWallet() {
        AssetHolder investor = new AssetHolder();
        investor.setWalletAddress("0xInvestor");
        givenAssetWithHolders(investor);

        assertThatThrownBy(() -> service(operatorEntity.toString())
                .register(assetId, "0xinvestor", "DESK", null, null, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(holderRepository, never()).save(any());
    }

    @Test
    @DisplayName("lending-market auto-registration without a configured entity is skipped, not guessed")
    void lendingMarketWithoutEntity_skipped() {
        UUID marketId = UUID.randomUUID();
        LendingMarket market = new LendingMarket();
        market.setMarketAddress("0xMarket");
        market.setCollateralAssetId(assetId);
        when(lendingMarketRepository.findById(marketId)).thenReturn(Optional.of(market));

        assertThat(service("").registerLendingMarket(marketId, null, "SYSTEM")).isFalse();
        verify(holderRepository, never()).save(any());
    }
}
