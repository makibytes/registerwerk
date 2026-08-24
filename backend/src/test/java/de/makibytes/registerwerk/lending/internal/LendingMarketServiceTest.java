package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.kyc.api.DefiInteropModel;
import de.makibytes.registerwerk.kyc.api.JurisdictionRequirementConfig;
import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.events.LendingMarketRegisteredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Lending market registration, catalog, and borrow-terms quoting")
class LendingMarketServiceTest {

    @Mock
    private LendingMarketRepository marketRepository;
    @Mock
    private AssetRepository assetRepository;
    @Mock
    private ChainConfigRepository chainConfigRepository;
    @Mock
    private RepoMarketOnchainReader onchainReader;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private JurisdictionRequirementConfig jurisdictionConfig;
    @Mock
    private LendingReleaseGate releaseGate;

    private LendingMarketService service;

    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final String marketAddress = "0x1111111111111111111111111111111111111a";

    @BeforeEach
    void setUp() {
        service = new LendingMarketService(
                marketRepository, assetRepository, chainConfigRepository, onchainReader, eventPublisher,
                jurisdictionConfig, releaseGate);
        lenient().when(marketRepository.save(any(LendingMarket.class))).thenAnswer(invocation -> {
            LendingMarket market = invocation.getArgument(0);
            if (market.getId() == null) market.setId(UUID.randomUUID());
            return market;
        });
    }

    @Test
    @DisplayName("registers a market, resolves jurisdiction from the linked asset, and emits an audit event")
    void registersMarketAndResolvesJurisdiction() {
        when(marketRepository.existsByChainConfigIdAndMarketAddressIgnoreCase(chainConfigId, marketAddress))
                .thenReturn(false);
        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        chainConfig.setChainType(ChainConfig.ChainType.EVM);
        chainConfig.setEnabled(true);
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));

        UUID assetId = UUID.randomUUID();
        Asset asset = new Asset();
        asset.setJurisdiction(Jurisdiction.DE_EWPG);
        asset.setName("Green Bond 2030");
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(onchainReader.marketParameters("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(parameters());

        var complianceMetadata = new JurisdictionRequirementConfig.ComplianceMetadata(
                List.of("OPEN_SANCTIONS"), "BaFin", "https://bafin.example/dora", false, false,
                Duration.ofDays(365), 25.0, Duration.ofDays(3650), DefiInteropModel.NOMINEE_POOL, false);
        var profile = new JurisdictionRequirementConfig.JurisdictionProfile(
                Jurisdiction.DE_EWPG, List.of(), complianceMetadata);
        when(jurisdictionConfig.getProfile(Jurisdiction.DE_EWPG)).thenReturn(profile);

        var view = service.registerMarket(
                chainConfigId, marketAddress, null, assetId, "aueur", actorId, "REGISTRY_ADMIN");

        assertThat(view.market().getMarketAddress()).isEqualTo(marketAddress);
        assertThat(view.market().getMaxLtvBps()).isEqualTo(7000);
        assertThat(view.jurisdiction()).isEqualTo(Jurisdiction.DE_EWPG);
        assertThat(view.collateralAssetName()).isEqualTo("Green Bond 2030");
        assertThat(view.micarApplicable()).isFalse();
        assertThat(view.defiInteropModel()).isEqualTo(DefiInteropModel.NOMINEE_POOL);

        ArgumentCaptor<LendingMarketRegisteredEvent> captor =
                ArgumentCaptor.forClass(LendingMarketRegisteredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("LENDING_MARKET_REGISTERED");
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("rejects registering the same market address twice on the same chain")
    void rejectsDuplicateMarket() {
        when(marketRepository.existsByChainConfigIdAndMarketAddressIgnoreCase(chainConfigId, marketAddress))
                .thenReturn(true);

        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, UUID.randomUUID(), null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("rejects a market whose collateral asset is not registered")
    void rejectsUnknownCollateralAsset() {
        when(marketRepository.existsByChainConfigIdAndMarketAddressIgnoreCase(chainConfigId, marketAddress))
                .thenReturn(false);
        ChainConfig chain = new ChainConfig();
        chain.setChainType(ChainConfig.ChainType.EVM);
        chain.setEnabled(true);
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain));
        UUID missingAsset = UUID.randomUUID();
        when(assetRepository.findById(missingAsset)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, missingAsset, null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Asset");
    }

    @Test
    @DisplayName("listMarkets filters by status when provided")
    void listMarketsFiltersByStatus() {
        LendingMarket market = new LendingMarket();
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setStatus(LendingMarketStatus.ACTIVE);
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        when(onchainReader.borrowPaused("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(false);

        var results = service.listMarkets(LendingMarketStatus.ACTIVE);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).market().getId()).isEqualTo(market.getId());
    }

    @Test
    @DisplayName("quote computes max-borrow from live oracle price and the lower origination LTV")
    void quoteComputesMaxBorrowFromLivePriceAndMaxLtv() {
        UUID marketId = UUID.randomUUID();
        LendingMarket market = new LendingMarket();
        market.setId(marketId);
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setCollateralTokenAddress("0xcollateral");
        market.setLoanTokenAddress("0xloan");
        market.setPriceOracleAddress("0xoracle");
        market.setMaxLtvBps(7000);
        market.setLltvBps(8000);
        market.setMaxPriceAgeSeconds(BigInteger.valueOf(3600));
        market.setStatus(LendingMarketStatus.ACTIVE);
        when(marketRepository.findById(marketId)).thenReturn(Optional.of(market));

        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));

        when(onchainReader.price("ETHEREUM_SEPOLIA", "0xoracle", "0xcollateral"))
                .thenReturn(new RepoMarketOnchainReader.PriceMark(
                        BigInteger.valueOf(100_000_000L), BigInteger.valueOf(java.time.Instant.now().getEpochSecond())));
        when(onchainReader.utilization("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(BigInteger.valueOf(500_000_000_000_000_000L));
        when(onchainReader.borrowRate("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(BigInteger.valueOf(110_000_000_000_000_000L));
        when(onchainReader.availableLiquidity("ETHEREUM_SEPOLIA", marketAddress, "0xloan"))
                .thenReturn(BigInteger.valueOf(20_000_000_000L));

        var quote = service.quote(marketId, BigInteger.valueOf(100));

        // 100 units * 100e6 price = 10_000e6 collateral value; 70% origination cap = 7_000e6.
        assertThat(quote.maxBorrowAmount()).isEqualTo(BigInteger.valueOf(7_000_000_000L));
        assertThat(quote.pricePerUnit()).isEqualTo(BigInteger.valueOf(100_000_000L));
        assertThat(quote.oracleReliable()).isTrue();
    }

    @Test
    @DisplayName("quote never advertises more than the market can actually lend")
    void quoteIsCappedAtAvailableLiquidity() {
        UUID marketId = UUID.randomUUID();
        LendingMarket market = new LendingMarket();
        market.setId(marketId);
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setCollateralTokenAddress("0xcollateral");
        market.setLoanTokenAddress("0xloan");
        market.setPriceOracleAddress("0xoracle");
        market.setMaxLtvBps(7000);
        market.setLltvBps(8000);
        market.setMaxPriceAgeSeconds(BigInteger.ZERO);
        market.setStatus(LendingMarketStatus.ACTIVE);
        when(marketRepository.findById(marketId)).thenReturn(Optional.of(market));

        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        when(onchainReader.price("ETHEREUM_SEPOLIA", "0xoracle", "0xcollateral"))
                .thenReturn(new RepoMarketOnchainReader.PriceMark(
                        BigInteger.valueOf(100_000_000L), BigInteger.valueOf(1)));
        when(onchainReader.utilization("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(BigInteger.ZERO);
        when(onchainReader.borrowRate("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(BigInteger.ZERO);
        when(onchainReader.availableLiquidity("ETHEREUM_SEPOLIA", marketAddress, "0xloan"))
                .thenReturn(BigInteger.valueOf(2_000_000_000L));

        var quote = service.quote(marketId, BigInteger.valueOf(100));

        assertThat(quote.maxBorrowAmount()).isEqualTo(BigInteger.valueOf(2_000_000_000L));
        assertThat(quote.availableLiquidity()).isEqualTo(BigInteger.valueOf(2_000_000_000L));
        assertThat(quote.oracleReliable()).isTrue();
    }

    private RepoMarketOnchainReader.MarketParameters parameters() {
        return new RepoMarketOnchainReader.MarketParameters(
                "0x3333333333333333333333333333333333333333",
                "0x2222222222222222222222222222222222222222",
                "0x4444444444444444444444444444444444444444",
                7000, 8000, 500,
                BigInteger.valueOf(20_000_000_000_000_000L),
                BigInteger.valueOf(180_000_000_000_000_000L),
                BigInteger.valueOf(3600), BigInteger.valueOf(7200), 6);
    }

    @Test
    @DisplayName("an ACTIVE market reads as PAUSED when the on-chain borrowPaused flag is set")
    void activeMarketReflectsOnchainBorrowPaused() {
        LendingMarket market = new LendingMarket();
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setStatus(LendingMarketStatus.ACTIVE);
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));

        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        when(onchainReader.borrowPaused("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(true);

        var results = service.listMarkets(LendingMarketStatus.ACTIVE);

        assertThat(results).isEmpty();
        assertThat(market.getStatus()).isEqualTo(LendingMarketStatus.ACTIVE);
    }

    @Test
    @DisplayName("an ACTIVE market with borrowPaused=false stays ACTIVE")
    void activeMarketStaysActiveWhenNotPaused() {
        LendingMarket market = new LendingMarket();
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setStatus(LendingMarketStatus.ACTIVE);
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));

        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        when(onchainReader.borrowPaused("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(false);

        var results = service.listMarkets(LendingMarketStatus.ACTIVE);

        assertThat(results.get(0).effectiveStatus()).isEqualTo(LendingMarketStatus.ACTIVE);
    }

    @Test
    @DisplayName("a RETIRED market never triggers an on-chain read — its persisted status is authoritative")
    void retiredMarketSkipsOnchainCheck() {
        LendingMarket market = new LendingMarket();
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setStatus(LendingMarketStatus.RETIRED);
        when(marketRepository.findByStatus(LendingMarketStatus.RETIRED)).thenReturn(List.of(market));

        var results = service.listMarkets(LendingMarketStatus.RETIRED);

        assertThat(results.get(0).effectiveStatus()).isEqualTo(LendingMarketStatus.RETIRED);
        verifyNoInteractions(onchainReader);
    }

    @Test
    @DisplayName("an ACTIVE discovery query hides a market when its on-chain pause status cannot be verified")
    void onchainReadFailureFailsClosedForActiveDiscovery() {
        LendingMarket market = new LendingMarket();
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setStatus(LendingMarketStatus.ACTIVE);
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.empty());

        var results = service.listMarkets(LendingMarketStatus.ACTIVE);

        assertThat(results).isEmpty();
    }
}
