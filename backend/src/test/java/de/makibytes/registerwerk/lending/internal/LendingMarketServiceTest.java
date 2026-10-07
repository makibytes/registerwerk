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
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    @Mock
    private de.makibytes.registerwerk.blockchain.api.ContractAddressConfig contractAddresses;
    @Mock
    private de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository deploymentRepository;
    @Mock
    private de.makibytes.registerwerk.payment.api.PaymentRailRepository railRepository;
    @Mock
    private de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository railAddressRepository;
    @Mock
    private de.makibytes.registerwerk.customer.api.LegalEntityRepository legalEntityRepository;
    @Mock
    private de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository bondTermsRepository;
    private final LendingProperties properties = new LendingProperties();

    private static final String FACTORY = "0x9999999999999999999999999999999999999999";
    private static final String COLLATERAL_TOKEN = "0x2222222222222222222222222222222222222222";

    private LendingMarketService service;

    private final UUID chainConfigId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final String marketAddress = "0x1111111111111111111111111111111111111a";

    @BeforeEach
    void setUp() {
        service = new LendingMarketService(
                marketRepository, assetRepository, chainConfigRepository, onchainReader, eventPublisher,
                jurisdictionConfig, releaseGate, properties, contractAddresses, deploymentRepository,
                railRepository, railAddressRepository, legalEntityRepository, bondTermsRepository);
        stubValidBinding();
        lenient().when(marketRepository.save(any(LendingMarket.class))).thenAnswer(invocation -> {
            LendingMarket market = invocation.getArgument(0);
            if (market.getId() == null) market.setId(UUID.randomUUID());
            return market;
        });
    }

    private de.makibytes.registerwerk.payment.api.PaymentRail rail;
    private de.makibytes.registerwerk.payment.api.PaymentRailChainAddress railAddress;
    private de.makibytes.registerwerk.deployment.api.AssetDeployment deployment;

    /** Defaults under which binding verification passes; tests break one link at a time. */
    private void stubValidBinding() {
        lenient().when(contractAddresses.findRepoMarketFactory("ETHEREUM_SEPOLIA")).thenReturn(Optional.of(FACTORY));
        lenient().when(onchainReader.isFactoryMarket("ETHEREUM_SEPOLIA", FACTORY, marketAddress)).thenReturn(true);
        deployment = new de.makibytes.registerwerk.deployment.api.AssetDeployment();
        deployment.setChainConfigId(chainConfigId);
        deployment.setContractAddress(COLLATERAL_TOKEN);
        deployment.setDeploymentStatus(de.makibytes.registerwerk.deployment.api.AssetDeployment.DeploymentStatus.CONFIRMED);
        lenient().when(deploymentRepository.findByAssetId(any())).thenAnswer(i -> List.of(deployment));
        rail = new de.makibytes.registerwerk.payment.api.PaymentRail();
        rail.setId(UUID.randomUUID());
        rail.setCode("aueur");
        rail.setRailType(de.makibytes.registerwerk.payment.api.PaymentRailType.STABLECOIN);
        rail.setDecimals(6);
        rail.setEnabled(true);
        lenient().when(railRepository.findByCode("aueur")).thenAnswer(i -> Optional.of(rail));
        railAddress = new de.makibytes.registerwerk.payment.api.PaymentRailChainAddress();
        railAddress.setChainConfigId(chainConfigId);
        railAddress.setTokenAddress(LOAN_TOKEN);
        lenient().when(railAddressRepository.findByPaymentRailId(any())).thenAnswer(i -> List.of(railAddress));
    }

    private Asset issuedAsset() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setStatus(de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED);
        return asset;
    }

    private void stubRegistration(Asset asset) {
        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setId(chainConfigId);
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        chainConfig.setChainType(ChainConfig.ChainType.EVM);
        chainConfig.setEnabled(true);
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(onchainReader.marketParameters("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(parameters());
    }

    @Test
    @DisplayName("5B-09: refuses a market whose collateral token is not the asset's confirmed deployment")
    void rejectsCollateralTokenMismatch() {
        Asset asset = issuedAsset();
        stubRegistration(asset);
        deployment.setContractAddress("0x1234123412341234123412341234123412341234");

        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, asset.getId(), "aueur", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirmed deployment");
        verify(marketRepository, never()).save(any());
    }

    @Test
    @DisplayName("5B-09: refuses a market that the configured factory did not deploy")
    void rejectsNonFactoryMarket() {
        Asset asset = issuedAsset();
        stubRegistration(asset);
        when(onchainReader.isFactoryMarket("ETHEREUM_SEPOLIA", FACTORY, marketAddress)).thenReturn(false);

        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, asset.getId(), "aueur", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("factory");
        verify(marketRepository, never()).save(any());
    }

    @Test
    @DisplayName("5B-09: fails closed when no factory is configured for the chain")
    void rejectsWhenNoFactoryConfigured() {
        Asset asset = issuedAsset();
        stubRegistration(asset);
        when(contractAddresses.findRepoMarketFactory("ETHEREUM_SEPOLIA")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, asset.getId(), "aueur", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No repo-market factory");
    }

    @Test
    @DisplayName("5B-09: refuses a loan token that is not the rail's token on this chain, or other decimals")
    void rejectsLoanRailMismatch() {
        Asset asset = issuedAsset();
        stubRegistration(asset);
        railAddress.setTokenAddress("0x7777777777777777777777777777777777777777");
        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, asset.getId(), "aueur", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token of rail");

        railAddress.setTokenAddress(LOAN_TOKEN);
        rail.setDecimals(18);
        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, asset.getId(), "aueur", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimals");
    }

    @Test
    @DisplayName("5B-09: refuses an asset that is not ISSUED")
    void rejectsNonIssuedAsset() {
        Asset asset = issuedAsset();
        asset.setStatus(de.makibytes.registerwerk.asset.api.AssetStatus.DRAFT);
        stubRegistration(asset);

        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, asset.getId(), "aueur", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ISSUED");
    }

    @Test
    @DisplayName("T5-13: quote refuses RETAIL and unclassified entities, admits professional clients")
    void quoteRefusesRetail() {
        UUID retail = UUID.randomUUID();
        UUID unclassified = UUID.randomUUID();
        UUID professional = UUID.randomUUID();
        var e1 = new de.makibytes.registerwerk.customer.api.LegalEntity();
        e1.setClientCategory(de.makibytes.registerwerk.customer.api.ClientCategory.RETAIL);
        var e2 = new de.makibytes.registerwerk.customer.api.LegalEntity();
        var e3 = new de.makibytes.registerwerk.customer.api.LegalEntity();
        e3.setClientCategory(de.makibytes.registerwerk.customer.api.ClientCategory.PROFESSIONAL);
        when(legalEntityRepository.findById(retail)).thenReturn(Optional.of(e1));
        when(legalEntityRepository.findById(unclassified)).thenReturn(Optional.of(e2));
        when(legalEntityRepository.findById(professional)).thenReturn(Optional.of(e3));

        assertThatThrownBy(() -> service.requireLendingEligible(retail))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.requireLendingEligible(unclassified))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        service.requireLendingEligible(professional);
    }

    @Test
    @DisplayName("5B-10: a market with a recorded collateral shortfall reads as PAUSED (COLLATERAL_SHORTFALL)")
    void shortfallMarketIsPaused() {
        LendingMarket market = registeredMarket(7500);
        market.setCollateralShortfall(true);
        when(marketRepository.findById(market.getId())).thenReturn(Optional.of(market));
        when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(LOAN_TOKEN);
        when(onchainReader.oracleMaxDeviationBps("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(BigInteger.valueOf(2000));
        when(onchainReader.operatorOrg("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(OPERATOR_ORG);
        when(onchainReader.treasury("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(TREASURY);

        var view = service.getMarket(market.getId());

        assertThat(view.effectiveStatus()).isEqualTo(LendingMarketStatus.PAUSED);
        assertThat(view.pauseReason()).isEqualTo("COLLATERAL_SHORTFALL");
        assertThatThrownBy(() -> service.requireOperational(market)).isInstanceOf(IllegalStateException.class);
    }

    // ── 9A-06: live collateral check ─────────────────────────────────────────────

    private LendingMarket lenientMarket() {
        LendingMarket market = new LendingMarket();
        market.setBindingVerified(true);
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setLoanTokenAddress(LOAN_TOKEN);
        market.setPriceOracleAddress("0xoracle");
        market.setLltvBps(7500);
        market.setLiquidationBonusBps(500);
        market.setStatus(LendingMarketStatus.ACTIVE);
        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        lenient().when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        return market;
    }

    private LendingMarket marketWithCollateral(de.makibytes.registerwerk.asset.api.AssetStatus status) {
        LendingMarket market = lenientMarket();
        UUID assetId = UUID.randomUUID();
        market.setCollateralAssetId(assetId);
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setStatus(status);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(marketRepository.findById(market.getId())).thenReturn(Optional.of(market));
        lenient().when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(LOAN_TOKEN);
        lenient().when(onchainReader.oracleMaxDeviationBps("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(BigInteger.valueOf(2000));
        lenient().when(onchainReader.operatorOrg("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(OPERATOR_ORG);
        lenient().when(onchainReader.treasury("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(TREASURY);
        return market;
    }

    @Test
    @DisplayName("9A-06: a market whose collateral asset is SUSPENDED reads as PAUSED immediately (no job, no RPC)")
    void suspendedCollateralMakesMarketPausedImmediately() {
        LendingMarket market = marketWithCollateral(de.makibytes.registerwerk.asset.api.AssetStatus.SUSPENDED);

        var view = service.getMarket(market.getId());

        assertThat(view.effectiveStatus()).isEqualTo(LendingMarketStatus.PAUSED);
        assertThat(view.pauseReason()).isEqualTo("COLLATERAL_ASSET_SUSPENDED");
        verify(onchainReader, never()).borrowPaused(any(), any());
    }

    @Test
    @DisplayName("9A-06: a bond that is OVERDUE or DEFAULTED pauses the market (COLLATERAL_BOND_<STATUS>); an ACTIVE bond does not")
    void overdueBondPausesMarketImmediately() {
        LendingMarket market = marketWithCollateral(de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED);
        var terms = new de.makibytes.registerwerk.deployment.api.AssetBondTerms();
        terms.setBondStatus(de.makibytes.registerwerk.deployment.api.BondStatus.OVERDUE);
        when(bondTermsRepository.findById(market.getCollateralAssetId())).thenReturn(Optional.of(terms));

        var view = service.getMarket(market.getId());

        assertThat(view.effectiveStatus()).isEqualTo(LendingMarketStatus.PAUSED);
        assertThat(view.pauseReason()).isEqualTo("COLLATERAL_BOND_OVERDUE");

        terms.setBondStatus(de.makibytes.registerwerk.deployment.api.BondStatus.ACTIVE);
        assertThat(service.collateralUnavailableReason(market)).isEmpty();
    }

    @Test
    @DisplayName("9A-06: reverifyMarket checks a single market and reports retired markets without touching the chain")
    void reverifyMarketHandlesOneMarket() {
        LendingMarket retired = lenientMarket();
        retired.setStatus(LendingMarketStatus.RETIRED);
        when(marketRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

        var result = service.reverifyMarket(retired.getId());

        assertThat(result.verified()).isFalse();
        verifyNoInteractions(onchainReader);
    }

    @Test
    @DisplayName("9A-06: quoting a market whose collateral asset is not ISSUED is refused")
    void quoteRefusedForSuspendedCollateral() {
        LendingMarket market = marketWithCollateral(de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFER_PENDING);

        assertThatThrownBy(() -> service.quote(market.getId(), BigInteger.TEN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not operational");
    }

    @Test
    @DisplayName("8A-02: the market view carries the chain's numeric id and name; null-safe when the config is gone")
    void viewCarriesChainIdAndName() {
        LendingMarket market = registeredMarket(7500);
        when(marketRepository.findById(market.getId())).thenReturn(Optional.of(market));
        lenient().when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(LOAN_TOKEN);
        lenient().when(onchainReader.oracleMaxDeviationBps("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(BigInteger.valueOf(2000));
        lenient().when(onchainReader.operatorOrg("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(OPERATOR_ORG);
        lenient().when(onchainReader.treasury("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(TREASURY);
        ChainConfig chain = new ChainConfig();
        chain.setId(chainConfigId);
        chain.setIdentifier("ETHEREUM_SEPOLIA");
        chain.setDisplayName("Ethereum Sepolia");
        chain.setChainId(11155111L);
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chain));

        var view = service.getMarket(market.getId());
        assertThat(view.chainId()).isEqualTo(11155111L);
        assertThat(view.chainName()).isEqualTo("Ethereum Sepolia");

        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.empty());
        var gone = service.getMarket(market.getId());
        assertThat(gone.chainId()).isNull();
        assertThat(gone.chainName()).isNull();
    }

    @Test
    @DisplayName("5B-09: unverified markets are hidden from customers but visible to operators")
    void unverifiedMarketHiddenFromCustomers() {
        LendingMarket market = registeredMarket(7500);
        market.setBindingVerified(false);
        when(marketRepository.findAll()).thenReturn(List.of(market));
        when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(LOAN_TOKEN);
        when(onchainReader.oracleMaxDeviationBps("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(BigInteger.valueOf(2000));
        when(onchainReader.operatorOrg("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(OPERATOR_ORG);
        when(onchainReader.treasury("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(TREASURY);

        assertThat(service.listMarkets(null, false)).isEmpty();
        assertThat(service.listMarkets(null, true)).hasSize(1);
    }

    @Test
    @DisplayName("H11: a market row nobody has verified is not treated as verified (fail-closed default)")
    void freshRowIsUnverifiedByDefault() {
        assertThat(new LendingMarket().isBindingVerified()).isFalse();
    }

    private void stubReverifyRpcDown(LendingMarket market) {
        market.setCollateralAssetId(UUID.randomUUID());
        when(marketRepository.findAll()).thenReturn(List.of(market));
        when(assetRepository.findById(market.getCollateralAssetId())).thenReturn(Optional.of(issuedAsset()));
        when(onchainReader.isFactoryMarket(any(), any(), any())).thenThrow(new IllegalStateException("rpc down"));
    }

    @Test
    @DisplayName("H11: an RPC error during re-verification leaves a verified row unchanged but reports it as NOT verified")
    void rpcErrorDuringReverifyIsFlaggedNotReportedAsVerified() {
        LendingMarket market = registeredMarket(7500);
        market.setBindingVerified(true);
        Instant verifiedAt = Instant.now().minusSeconds(3600);
        market.setBindingVerifiedAt(verifiedAt);
        stubReverifyRpcDown(market);

        List<LendingMarketService.ReverifyResult> results = service.reverifyMarkets();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).verified()).isFalse();
        assertThat(results.get(0).failure()).contains("not checked");
        assertThat(market.isBindingVerified()).isTrue();
        assertThat(market.getBindingVerifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    @DisplayName("H11: an RPC error never promotes a legacy row that has no recorded successful verification")
    void rpcErrorKeepsLegacyRowUnverified() {
        LendingMarket market = registeredMarket(7500);
        market.setBindingVerified(true); // what V31 gave every pre-existing row
        market.setBindingVerifiedAt(null);
        stubReverifyRpcDown(market);

        List<LendingMarketService.ReverifyResult> results = service.reverifyMarkets();

        assertThat(results.get(0).verified()).isFalse();
        assertThat(market.isBindingVerified()).isFalse();
    }

    @Test
    @DisplayName("H11: unverified markets, and legacy ones whose reads succeed, need the on-chain borrow pause; read failures do not")
    void onchainBorrowPauseIsRequiredForUnverifiedAndLegacyMarkets() {
        LendingMarket unverified = registeredMarket(7500);
        unverified.setBindingVerified(false);
        assertThat(service.requiresOnchainBorrowPause(unverified)).isTrue();

        LendingMarket sound = registeredMarket(7500);
        sound.setBindingVerified(true);
        when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(LOAN_TOKEN);
        when(onchainReader.oracleMaxDeviationBps("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(BigInteger.valueOf(2000));
        when(onchainReader.operatorOrg("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(OPERATOR_ORG);
        when(onchainReader.treasury("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(TREASURY);
        assertThat(service.requiresOnchainBorrowPause(sound)).isFalse();

        LendingMarket legacy = registeredMarket(8000); // lltv x (1 + bonus) exceeds the oracle haircut
        legacy.setBindingVerified(true);
        assertThat(service.requiresOnchainBorrowPause(legacy)).isTrue();

        LendingMarket unreadable = registeredMarket(7500);
        unreadable.setBindingVerified(true);
        when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenThrow(new IllegalStateException("rpc down"));
        assertThat(service.requiresOnchainBorrowPause(unreadable)).isFalse();
    }

    @Test
    @DisplayName("registers a market, resolves jurisdiction from the linked asset, and emits an audit event")
    void registersMarketAndResolvesJurisdiction() {
        when(marketRepository.existsByChainConfigIdAndMarketAddressIgnoreCase(chainConfigId, marketAddress))
                .thenReturn(false);
        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setId(chainConfigId);
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        chainConfig.setChainType(ChainConfig.ChainType.EVM);
        chainConfig.setEnabled(true);
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));

        UUID assetId = UUID.randomUUID();
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setStatus(de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED);
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
        market.setBindingVerified(true); // H11: a row is unverified by default
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
        market.setBindingVerified(true); // H11: a row is unverified by default
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
        market.setBindingVerified(true); // H11: a row is unverified by default
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

    private static final String LOAN_TOKEN = "0x3333333333333333333333333333333333333333";
    private static final String OPERATOR_ORG = "0x5555555555555555555555555555555555555555";
    private static final String TREASURY = "0x6666666666666666666666666666666666666666";

    private RepoMarketOnchainReader.MarketParameters parameters() {
        // 75% LLTV × 1.05 = 0.7875 ≤ 1 − 20% oracle tolerance.
        return parameters(7500, LOAN_TOKEN, BigInteger.valueOf(2000));
    }

    private RepoMarketOnchainReader.MarketParameters parameters(
            int lltvBps, String oracleQuoteToken, BigInteger oracleMaxDeviationBps) {
        return new RepoMarketOnchainReader.MarketParameters(
                LOAN_TOKEN,
                "0x2222222222222222222222222222222222222222",
                "0x4444444444444444444444444444444444444444",
                7000, lltvBps, 500,
                BigInteger.valueOf(20_000_000_000_000_000L),
                BigInteger.valueOf(180_000_000_000_000_000L),
                BigInteger.valueOf(3600), BigInteger.valueOf(7200), 6,
                OPERATOR_ORG, TREASURY, oracleQuoteToken, oracleMaxDeviationBps);
    }

    private void stubRegistrableChainAndAsset(UUID assetId) {
        when(marketRepository.existsByChainConfigIdAndMarketAddressIgnoreCase(chainConfigId, marketAddress))
                .thenReturn(false);
        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        chainConfig.setChainType(ChainConfig.ChainType.EVM);
        chainConfig.setEnabled(true);
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(new Asset()));
    }

    @Test
    @DisplayName("refuses to register a market whose LLTV × (1 + bonus) exceeds 1 − the oracle's deviation cap")
    void rejectsMarketWhoseBonusEatsTheDeviationHaircut() {
        UUID assetId = UUID.randomUUID();
        stubRegistrableChainAndAsset(assetId);
        // The former demo parameters: 80% × 1.05 = 0.84 > 1 − 0.20.
        when(onchainReader.marketParameters("ETHEREUM_SEPOLIA", marketAddress))
                .thenReturn(parameters(8000, LOAN_TOKEN, BigInteger.valueOf(2000)));

        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, assetId, "aueur", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviation haircut");
        verify(marketRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses to register a market whose oracle quotes another token than the loan token")
    void rejectsMarketWithOracleQuotedInAnotherToken() {
        UUID assetId = UUID.randomUUID();
        stubRegistrableChainAndAsset(assetId);
        when(onchainReader.marketParameters("ETHEREUM_SEPOLIA", marketAddress))
                .thenReturn(parameters(7500, "0x7777777777777777777777777777777777777777", BigInteger.valueOf(2000)));

        assertThatThrownBy(() -> service.registerMarket(
                chainConfigId, marketAddress, null, assetId, "usdc", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loan token");
        verify(marketRepository, never()).save(any());
    }

    private LendingMarket registeredMarket(int lltvBps) {
        LendingMarket market = new LendingMarket();
        market.setBindingVerified(true); // H11: a row is unverified by default
        market.setId(UUID.randomUUID());
        market.setChainConfigId(chainConfigId);
        market.setMarketAddress(marketAddress);
        market.setLoanTokenAddress(LOAN_TOKEN);
        market.setPriceOracleAddress("0xoracle");
        market.setLltvBps(lltvBps);
        market.setLiquidationBonusBps(500);
        market.setStatus(LendingMarketStatus.ACTIVE);
        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setIdentifier("ETHEREUM_SEPOLIA");
        when(chainConfigRepository.findById(chainConfigId)).thenReturn(Optional.of(chainConfig));
        return market;
    }

    @Test
    @DisplayName("an already-registered market failing the risk check stays listed but is flagged legacy")
    void alreadyRegisteredUnsoundMarketIsFlaggedLegacyNotHidden() {
        LendingMarket market = registeredMarket(8000);
        when(marketRepository.findByStatus(LendingMarketStatus.ACTIVE)).thenReturn(List.of(market));
        when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(LOAN_TOKEN);
        when(onchainReader.oracleMaxDeviationBps("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(BigInteger.valueOf(2000));
        when(onchainReader.operatorOrg("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(OPERATOR_ORG);
        when(onchainReader.treasury("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(TREASURY);

        var results = service.listMarkets(LendingMarketStatus.ACTIVE);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).riskParametersLegacy()).isTrue();
        assertThat(results.get(0).effectiveStatus()).isEqualTo(LendingMarketStatus.ACTIVE);
    }

    @Test
    @DisplayName("a sound market is not legacy and exposes its operating org and treasury")
    void soundMarketExposesOperatorOrgAndTreasury() {
        LendingMarket market = registeredMarket(7500);
        when(marketRepository.findById(market.getId())).thenReturn(Optional.of(market));
        when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(LOAN_TOKEN);
        when(onchainReader.oracleMaxDeviationBps("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(BigInteger.valueOf(2000));
        when(onchainReader.operatorOrg("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(OPERATOR_ORG);
        when(onchainReader.treasury("ETHEREUM_SEPOLIA", marketAddress)).thenReturn(TREASURY);

        var view = service.getMarket(market.getId());

        assertThat(view.riskParametersLegacy()).isFalse();
        assertThat(view.operatorOrg()).isEqualTo(OPERATOR_ORG);
        assertThat(view.treasury()).isEqualTo(TREASURY);
    }

    @Test
    @DisplayName("a market predating operatorOrg (getter reverts) is flagged legacy")
    void marketWithoutOperatorOrgGetterIsLegacy() {
        LendingMarket market = registeredMarket(7500);
        when(marketRepository.findById(market.getId())).thenReturn(Optional.of(market));
        when(onchainReader.oracleQuoteToken("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(LOAN_TOKEN);
        when(onchainReader.oracleMaxDeviationBps("ETHEREUM_SEPOLIA", "0xoracle")).thenReturn(BigInteger.valueOf(2000));
        when(onchainReader.operatorOrg("ETHEREUM_SEPOLIA", marketAddress))
                .thenThrow(new IllegalStateException("Call to operatorOrg reverted"));

        var view = service.getMarket(market.getId());

        assertThat(view.riskParametersLegacy()).isTrue();
        assertThat(view.operatorOrg()).isNull();
    }

    @Test
    @DisplayName("risk check mirrors the contract: strict lltv × (1 + bonus) < 1, deviation haircut unless opted out")
    void riskParametersSoundMirrorsContractCheck() {
        BigInteger optOut = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
        assertThat(LendingMarketService.riskParametersSound(7500, 500, BigInteger.valueOf(2000))).isTrue();
        assertThat(LendingMarketService.riskParametersSound(8000, 500, BigInteger.valueOf(2000))).isFalse();
        assertThat(LendingMarketService.riskParametersSound(8000, 500, BigInteger.valueOf(1500))).isTrue();
        assertThat(LendingMarketService.riskParametersSound(9500, 500, optOut)).isTrue();
        assertThat(LendingMarketService.riskParametersSound(9600, 500, optOut)).isFalse();
        assertThat(LendingMarketService.riskParametersSound(5000, 0, BigInteger.valueOf(10_000))).isFalse();
        assertThat(LendingMarketService.riskParametersSound(null, 500, BigInteger.valueOf(2000))).isFalse();
    }

    @Test
    @DisplayName("an ACTIVE market reads as PAUSED when the on-chain borrowPaused flag is set")
    void activeMarketReflectsOnchainBorrowPaused() {
        LendingMarket market = new LendingMarket();
        market.setBindingVerified(true); // H11: a row is unverified by default
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
        market.setBindingVerified(true); // H11: a row is unverified by default
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
        market.setBindingVerified(true); // H11: a row is unverified by default
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
        market.setBindingVerified(true); // H11: a row is unverified by default
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
