package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.lending.api.LendingMarket;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.api.LendingPosition;
import de.makibytes.registerwerk.lending.api.LendingPositionRepository;
import de.makibytes.registerwerk.lending.api.LendingPositionStatus;
import de.makibytes.registerwerk.lending.api.LendingSupplyPosition;
import de.makibytes.registerwerk.lending.api.LendingSupplyPositionRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Lending position read-model refresh")
class LendingPositionServiceTest {

    @Mock
    private LendingMarketRepository marketRepository;
    @Mock
    private LendingPositionRepository positionRepository;
    @Mock
    private LendingSupplyPositionRepository supplyPositionRepository;
    @Mock
    private OrgMemberWalletRepository memberWalletRepository;
    @Mock
    private RepoMarketOnchainReader onchainReader;
    @Mock
    private LendingMarketService marketService;
    @Mock
    private RepoMarketEventReader eventReader;
    @Mock
    private LendingReleaseGate releaseGate;

    private LendingPositionService service;

    private final UUID appUserId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();
    private final String walletAddress = "0xabc0000000000000000000000000000000000a";
    private final String marketAddress = "0xmarket000000000000000000000000000000a";

    @BeforeEach
    void setUp() {
        service = new LendingPositionService(
                marketRepository, positionRepository, supplyPositionRepository, memberWalletRepository,
                onchainReader, marketService, eventReader, releaseGate);
        lenient().when(positionRepository.save(any(LendingPosition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(supplyPositionRepository.save(any(LendingSupplyPosition.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private OrgMemberWallet activeWallet() {
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setChainConfigId(chainConfigId);
        wallet.setWalletAddress(walletAddress);
        wallet.setAppUserId(appUserId);
        wallet.setStatus(MemberWalletStatus.ACTIVE);
        return wallet;
    }

    private LendingMarket activeMarket() {
        LendingMarket market = new LendingMarket();
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setStatus(LendingMarketStatus.ACTIVE);
        return market;
    }

    @Test
    @DisplayName("returns no positions when the user has no bound wallets")
    void noWalletsMeansNoPositions() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of());

        var positions = service.refreshAndListMyPositions(appUserId);

        assertThat(positions).isEmpty();
    }

    @Test
    @DisplayName("caches a new OPEN position for a wallet with live on-chain debt")
    void cachesNewOpenPosition() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(activeWallet()));
        LendingMarket market = activeMarket();
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");
        when(positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress))
                .thenReturn(Optional.empty());
        when(onchainReader.positionCollateralAmount("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.valueOf(100));
        when(onchainReader.debtOf("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.valueOf(8_000_000_000L));
        when(onchainReader.healthFactor("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(new RepoMarketOnchainReader.HealthFactorReading(
                        BigInteger.valueOf(1_100_000_000_000_000_000L), true));

        var positions = service.refreshAndListMyPositions(appUserId);

        assertThat(positions).hasSize(1);
        LendingPosition position = positions.get(0);
        assertThat(position.getStatus()).isEqualTo(LendingPositionStatus.OPEN);
        assertThat(position.getCurrentDebt()).isEqualTo(BigInteger.valueOf(8_000_000_000L));
        assertThat(position.getHealthFactorWad()).isEqualTo(BigInteger.valueOf(1_100_000_000_000_000_000L));
        assertThat(position.getHealthFactorReliable()).isTrue();
    }

    @Test
    @DisplayName("caches a position with healthFactorReliable=false when the contract flags its own price as stale/unpriced (finding #8)")
    void cachesUnreliableHealthFactor() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(activeWallet()));
        LendingMarket market = activeMarket();
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");
        when(positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress))
                .thenReturn(Optional.empty());
        when(onchainReader.positionCollateralAmount("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.valueOf(100));
        when(onchainReader.debtOf("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.valueOf(8_000_000_000L));
        when(onchainReader.healthFactor("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(new RepoMarketOnchainReader.HealthFactorReading(
                        BigInteger.valueOf(900_000_000_000_000_000L), false));

        var positions = service.refreshAndListMyPositions(appUserId);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getHealthFactorReliable()).isFalse();
    }

    @Test
    @DisplayName("flips a previously-OPEN cached position to CLOSED once on-chain debt reaches zero, rather than skipping it")
    void flipsExistingPositionToClosedRatherThanSkippingIt() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(activeWallet()));
        LendingMarket market = activeMarket();
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");

        LendingPosition existing = new LendingPosition();
        existing.setId(UUID.randomUUID());
        existing.setMarketId(market.getId());
        existing.setWalletAddress(walletAddress);
        existing.setStatus(LendingPositionStatus.OPEN);
        existing.setCurrentDebt(BigInteger.valueOf(8_000_000_000L));
        existing.setCollateralAmount(BigInteger.valueOf(100));
        when(positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress))
                .thenReturn(Optional.of(existing));

        // Fully repaid: both amounts now read zero on-chain.
        when(onchainReader.positionCollateralAmount("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);
        when(onchainReader.debtOf("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);

        var positions = service.refreshAndListMyPositions(appUserId);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getStatus()).isEqualTo(LendingPositionStatus.CLOSED);
        assertThat(positions.get(0).getCurrentDebt()).isEqualTo(BigInteger.ZERO);
        verify(onchainReader, never()).healthFactor(any(), any(), any());
    }

    @Test
    @DisplayName("keeps durable status CLOSED when the subgraph only supplies an unfinalized liquidation hint")
    void doesNotPromoteSubgraphLiquidationHintToDurableStatus() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(activeWallet()));
        LendingMarket market = activeMarket();
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");

        LendingPosition existing = new LendingPosition();
        existing.setId(UUID.randomUUID());
        existing.setMarketId(market.getId());
        existing.setWalletAddress(walletAddress);
        existing.setStatus(LendingPositionStatus.OPEN);
        existing.setCurrentDebt(BigInteger.valueOf(8_000_000_000L));
        existing.setCollateralAmount(BigInteger.valueOf(100));
        when(positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress))
                .thenReturn(Optional.of(existing));

        when(onchainReader.positionCollateralAmount("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);
        when(onchainReader.debtOf("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);

        de.makibytes.registerwerk.chain.api.ChainConfig chainConfig = new de.makibytes.registerwerk.chain.api.ChainConfig();
        when(marketService.resolveChainConfig(chainConfigId)).thenReturn(chainConfig);
        when(eventReader.lastClosingEventHint(chainConfig, marketAddress, walletAddress))
                .thenReturn(Optional.of(new RepoMarketEventReader.UnfinalizedClosingEventHint(
                        "LIQUIDATED", "EVENT_DERIVED")));

        var positions = service.refreshAndListMyPositions(appUserId);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getStatus()).isEqualTo(LendingPositionStatus.CLOSED);
        verify(eventReader).lastClosingEventHint(chainConfig, marketAddress, walletAddress);
    }

    @Test
    @DisplayName("does not query the subgraph for a position that was already closed (only queries at the open->closed transition)")
    void doesNotQuerySubgraphForAlreadyClosedPosition() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(activeWallet()));
        LendingMarket market = activeMarket();
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");

        LendingPosition existing = new LendingPosition();
        existing.setId(UUID.randomUUID());
        existing.setMarketId(market.getId());
        existing.setWalletAddress(walletAddress);
        existing.setStatus(LendingPositionStatus.CLOSED);
        existing.setCurrentDebt(BigInteger.ZERO);
        existing.setCollateralAmount(BigInteger.ZERO);
        when(positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress))
                .thenReturn(Optional.of(existing));

        when(onchainReader.positionCollateralAmount("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);
        when(onchainReader.debtOf("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);

        var positions = service.refreshAndListMyPositions(appUserId);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getStatus()).isEqualTo(LendingPositionStatus.CLOSED);
        verify(eventReader, never()).lastClosingEventHint(any(), any(), any());
        verify(marketService, never()).resolveChainConfig(any());
    }

    @Test
    @DisplayName("downgrades legacy LIQUIDATED rows whose canonical provenance was never stored")
    void failsLegacyLiquidatedStatusClosed() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(activeWallet()));
        LendingMarket market = activeMarket();
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");

        LendingPosition existing = new LendingPosition();
        existing.setId(UUID.randomUUID());
        existing.setMarketId(market.getId());
        existing.setWalletAddress(walletAddress);
        existing.setStatus(LendingPositionStatus.LIQUIDATED);
        existing.setCurrentDebt(BigInteger.ZERO);
        existing.setCollateralAmount(BigInteger.ZERO);
        when(positionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress))
                .thenReturn(Optional.of(existing));
        when(onchainReader.positionCollateralAmount("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);
        when(onchainReader.debtOf("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);

        var positions = service.refreshAndListMyPositions(appUserId);

        assertThat(positions).singleElement()
                .extracting(LendingPosition::getStatus)
                .isEqualTo(LendingPositionStatus.CLOSED);
        verify(eventReader, never()).lastClosingEventHint(any(), any(), any());
    }

    @Test
    @DisplayName("skips a wallet whose chain does not match the market's chain")
    void skipsWalletOnDifferentChain() {
        OrgMemberWallet otherChainWallet = activeWallet();
        otherChainWallet.setChainConfigId(UUID.randomUUID());
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(otherChainWallet));
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(activeMarket()));

        var positions = service.refreshAndListMyPositions(appUserId);

        assertThat(positions).isEmpty();
        verify(onchainReader, never()).debtOf(any(), any(), any());
    }

    @Test
    @DisplayName("caches a new supply position for a lender with a nonzero live claim")
    void cachesNewSupplyPosition() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(activeWallet()));
        LendingMarket market = activeMarket();
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");
        when(supplyPositionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress))
                .thenReturn(Optional.empty());
        when(onchainReader.supplyBalanceOf("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.valueOf(1_000_000_000L));

        var positions = service.refreshAndListMySupplyPositions(appUserId);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getCurrentClaim()).isEqualTo(BigInteger.valueOf(1_000_000_000L));
    }

    @Test
    @DisplayName("does not persist a supply position for a wallet that never supplied anything")
    void skipsNeverSuppliedWallet() {
        when(memberWalletRepository.findByAppUserId(appUserId)).thenReturn(List.of(activeWallet()));
        LendingMarket market = activeMarket();
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(marketService.resolveChainIdentifier(chainConfigId)).thenReturn("ETHEREUM_SEPOLIA");
        when(supplyPositionRepository.findByMarketIdAndWalletAddressIgnoreCase(market.getId(), walletAddress))
                .thenReturn(Optional.empty());
        when(onchainReader.supplyBalanceOf("ETHEREUM_SEPOLIA", marketAddress, walletAddress))
                .thenReturn(BigInteger.ZERO);

        var positions = service.refreshAndListMySupplyPositions(appUserId);

        assertThat(positions).isEmpty();
        verify(supplyPositionRepository, never()).save(any());
    }
}
