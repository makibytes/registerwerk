package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.api.RedemptionReadinessPort;
import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import de.makibytes.registerwerk.asset.events.AssetRedemptionIncompleteEvent;
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.deployment.api.IndexedTransferLookup;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AssetRedemptionListener unit tests (T3-01, Wave 0b C7)")
class AssetRedemptionListenerTest {

    private final AssetRepository assetRepository = mock(AssetRepository.class);
    private final AssetDeploymentRepository deploymentRepository = mock(AssetDeploymentRepository.class);
    private final AssetHolderRepository holderRepository = mock(AssetHolderRepository.class);
    private final TokenAdminPort tokenAdminPort = mock(TokenAdminPort.class);
    private final RedemptionReadinessPort redemptionReadiness = mock(RedemptionReadinessPort.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final AssetRedemptionBurnRepository burns = mock(AssetRedemptionBurnRepository.class);
    private final IndexedTransferLookup indexed = mock(IndexedTransferLookup.class);
    private final AssetLifecycleService lifecycle = mock(AssetLifecycleService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    /** The burn table, kept in memory: save() stores, the finders read it (the unique key is asset+deployment+wallet). */
    private final List<AssetRedemptionBurn> table = new ArrayList<>();

    private AssetRedemptionListener listener;

    @BeforeEach
    void setUp() {
        listener = new AssetRedemptionListener(assetRepository, deploymentRepository, holderRepository, tokenAdminPort,
                redemptionReadiness, eventPublisher, burns, indexed, lifecycle,
                new IsolatedTransactionExecutor(transactionManager));
        when(burns.save(any(AssetRedemptionBurn.class))).thenAnswer(inv -> {
            AssetRedemptionBurn b = inv.getArgument(0);
            if (!table.contains(b)) {
                table.add(b);
            }
            return b;
        });
        when(burns.findByAssetId(any())).thenAnswer(inv -> List.copyOf(table));
        when(burns.findByAssetIdAndDeploymentIdAndWalletAddress(any(), any(), any())).thenAnswer(inv -> table.stream()
                .filter(b -> b.getAssetId().equals(inv.getArgument(0)) && b.getDeploymentId().equals(inv.getArgument(1))
                        && b.getWalletAddress().equals(inv.getArgument(2)))
                .findFirst());
    }

    private static Asset asset(UUID id, TokenStandard standard) {
        Asset a = new Asset();
        a.setId(id);
        a.setAssetNumber("AST-2026-000042");
        a.setTokenStandard(standard);
        a.setStatus(AssetStatus.REDEMPTION_PENDING);
        return a;
    }

    private static AssetHolder holder(String wallet, BigDecimal nominal) {
        AssetHolder h = new AssetHolder();
        h.setWalletAddress(wallet);
        h.setNominalAmount(nominal);
        return h;
    }

    private static AssetDeployment deployment(UUID id) {
        AssetDeployment dep = new AssetDeployment();
        ReflectionTestUtils.setField(dep, "id", id);
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        return dep;
    }

    private static AssetRedeemedEvent event(UUID assetId) {
        return new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN");
    }

    private void givenErc20(UUID assetId, AssetDeployment... deployments) {
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset(assetId, TokenStandard.ERC20)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployments));
    }

    // ── dispatch ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dispatches a forceBurn per holder for an ERC-20 redemption; the asset is NOT completed on submission")
    void dispatchesBurnForAutomatedStandard() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        givenErc20(assetId, deployment(deploymentId));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(
                holder("0xaaa", new BigDecimal("500")),
                holder("0xbbb", BigDecimal.ZERO) // zero balance — must not dispatch a burn
        ));
        UUID txId = UUID.randomUUID();
        when(tokenAdminPort.forceBurn(eq(deploymentId), eq("0xaaa"), eq(BigInteger.valueOf(500)), any(), any(), eq("REGISTRY_ADMIN")))
                .thenReturn(txId);

        listener.onAssetRedeemed(event(assetId));

        verify(tokenAdminPort).forceBurn(eq(deploymentId), eq("0xaaa"), eq(BigInteger.valueOf(500)),
                any(String.class), any(UUID.class), eq("REGISTRY_ADMIN"));
        verify(tokenAdminPort, never()).forceBurn(any(), eq("0xbbb"), any(), any(), any(), any());
        assertThat(table).singleElement().satisfies(b -> {
            assertThat(b.getStatus()).isEqualTo(AssetRedemptionBurn.Status.SUBMITTED);
            assertThat(b.getTxId()).isEqualTo(txId);
            assertThat(b.getAmount()).isEqualByComparingTo("500");
        });
        // C7: submitted is not done - the asset stays REDEMPTION_PENDING until the burn is final
        verify(lifecycle, never()).completeRedemption(any(), anyInt());
    }

    @Test
    @DisplayName("dispatches forceBurnSingle with id=0 (not the now-guarded forceBurn) per holder for an ERC-1155 redemption")
    void dispatchesForceBurnSingleWithIdZeroForErc1155() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset(assetId, TokenStandard.ERC1155)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId)));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(holder("0xccc", new BigDecimal("250"))));

        listener.onAssetRedeemed(event(assetId));

        verify(tokenAdminPort).forceBurnSingle(eq(deploymentId), eq("0xccc"), eq(BigInteger.ZERO),
                eq(BigInteger.valueOf(250)), any(String.class), any(UUID.class), eq("REGISTRY_ADMIN"));
        verify(tokenAdminPort, never()).forceBurn(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a standard with no automated burn: nothing dispatched, the redemption completes (the incomplete event is the follow-up)")
    void skipsUnautomatedStandard() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset(assetId, TokenStandard.DAML_BOND_FIXED)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(UUID.randomUUID())));

        listener.onAssetRedeemed(event(assetId));

        verify(tokenAdminPort, never()).forceBurn(any(), any(), any(), any(), any(), any());
        verify(holderRepository, never()).findActiveByAssetId(any());
        verify(lifecycle).completeRedemption(assetId, 0);
    }

    @Test
    @DisplayName("an asset without an on-chain deployment has nothing to burn: the redemption completes")
    void completesWhenNoDeployment() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset(assetId, TokenStandard.ERC20)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of());

        listener.onAssetRedeemed(event(assetId));

        verify(tokenAdminPort, never()).forceBurn(any(), any(), any(), any(), any(), any());
        verify(lifecycle).completeRedemption(assetId, 0);
    }

    @Test
    @DisplayName("an asset that is not REDEMPTION_PENDING (completed already, or never started) is left alone")
    void ignoresAssetsThatAreNotRedeeming() {
        UUID assetId = UUID.randomUUID();
        Asset done = asset(assetId, TokenStandard.ERC20);
        done.setStatus(AssetStatus.REDEEMED);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(done));

        listener.onAssetRedeemed(event(assetId));

        verify(tokenAdminPort, never()).forceBurn(any(), any(), any(), any(), any(), any());
        verify(lifecycle, never()).completeRedemption(any(), anyInt());
    }

    // ── bond: who is burnt and how much ───────────────────────────────────────

    @Test
    @DisplayName("skipsNomineePoolAndUnsettledEntries: bond burns only paid holders; pool + unpaid reported (T3-01)")
    void skipsNomineePoolAndUnsettledEntries() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID caId = UUID.randomUUID();
        givenErc20(assetId, deployment(deploymentId));
        AssetHolder pool = holder("0xpool", new BigDecimal("300"));
        pool.setHolderKind(HolderKind.NOMINEE_POOL);
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(
                holder("0xPAID", new BigDecimal("500")), holder("0xunpaid", new BigDecimal("200")), pool));
        when(redemptionReadiness.settledRetirementAction(assetId)).thenReturn(Optional.of(
                new RedemptionReadinessPort.SettledRetirement(caId, Set.of("0xpaid"), Map.of("0xpaid", new BigDecimal("500")))));

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN",
                "eWpG §26", "CA", UUID.randomUUID(), caId));

        verify(tokenAdminPort).forceBurn(eq(deploymentId), eq("0xPAID"), eq(BigInteger.valueOf(500)),
                any(String.class), any(UUID.class), eq("REGISTRY_ADMIN"));
        verify(tokenAdminPort, never()).forceBurn(any(), eq("0xunpaid"), any(), any(), any(), any());
        verify(tokenAdminPort, never()).forceBurn(any(), eq("0xpool"), any(), any(), any(), any());
        ArgumentCaptor<AssetRedemptionIncompleteEvent> captor = ArgumentCaptor.forClass(AssetRedemptionIncompleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unburnt = (List<Map<String, Object>>) captor.getValue().payload().get("unburnt");
        assertThat(unburnt).extracting(m -> m.get("walletAddress")).containsExactlyInAnyOrder("0xunpaid", "0xpool");
    }

    private void bondWith(UUID assetId, UUID caId, String wallet, String registerNominal, String nominalAtRecord, AssetDeployment... deps) {
        givenErc20(assetId, deps);
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(holder(wallet, new BigDecimal(registerNominal))));
        when(redemptionReadiness.settledRetirementAction(assetId)).thenReturn(Optional.of(
                new RedemptionReadinessPort.SettledRetirement(caId, Set.of(wallet.toLowerCase()),
                        Map.of(wallet.toLowerCase(), new BigDecimal(nominalAtRecord)))));
    }

    @Test
    @DisplayName("C7: a holder who bought units after the record date is burnt only for the nominal the redemption paid (500 held, 350 at record date -> 350)")
    void burnIsCappedAtTheNominalAtRecordDate() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID caId = UUID.randomUUID();
        bondWith(assetId, caId, "0xpaid", "500", "350", deployment(deploymentId));

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN", "§26", "CA", UUID.randomUUID(), caId));

        verify(tokenAdminPort).forceBurn(eq(deploymentId), eq("0xpaid"), eq(BigInteger.valueOf(350)), any(), any(), any());
        verify(tokenAdminPort, never()).forceBurn(any(), any(), eq(BigInteger.valueOf(500)), any(), any(), any());
    }

    @Test
    @DisplayName("C7: a holder who sold units since the record date is burnt only what is left (200 held, 350 at record date -> 200)")
    void burnIsCappedAtTheCurrentBalance() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID caId = UUID.randomUUID();
        bondWith(assetId, caId, "0xpaid", "200", "350", deployment(deploymentId));

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN", "§26", "CA", UUID.randomUUID(), caId));

        verify(tokenAdminPort).forceBurn(eq(deploymentId), eq("0xpaid"), eq(BigInteger.valueOf(200)), any(), any(), any());
    }

    @Test
    @DisplayName("C7: with several deployments the burn is spread over EVERY deployment, each capped at the wallet's balance there (60 + 90 held, 100 paid -> 60 + 40)")
    void burnIsSpreadOverEveryDeployment() {
        UUID assetId = UUID.randomUUID();
        UUID depA = UUID.randomUUID();
        UUID depB = UUID.randomUUID();
        UUID caId = UUID.randomUUID();
        AssetDeployment first = deployment(depA);
        first.setDeployedAt(Instant.parse("2025-01-01T00:00:00Z"));
        AssetDeployment second = deployment(depB);
        second.setDeployedAt(Instant.parse("2025-02-01T00:00:00Z"));
        bondWith(assetId, caId, "0xpaid", "150", "100", second, first);
        when(indexed.finalizedBalance(depA, "0xpaid")).thenReturn(new BigDecimal("60"));
        when(indexed.finalizedBalance(depB, "0xpaid")).thenReturn(new BigDecimal("90"));

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN", "§26", "CA", UUID.randomUUID(), caId));

        verify(tokenAdminPort).forceBurn(eq(depA), eq("0xpaid"), eq(BigInteger.valueOf(60)), any(), any(), any());
        verify(tokenAdminPort).forceBurn(eq(depB), eq("0xpaid"), eq(BigInteger.valueOf(40)), any(), any(), any());
        assertThat(table).hasSize(2);
    }

    @Test
    @DisplayName("C7: paid units that are on no indexed deployment balance are reported, not silently dropped")
    void unplaceableUnitsAreReported() {
        UUID assetId = UUID.randomUUID();
        UUID depA = UUID.randomUUID();
        UUID depB = UUID.randomUUID();
        UUID caId = UUID.randomUUID();
        bondWith(assetId, caId, "0xpaid", "100", "100", deployment(depA), deployment(depB));
        when(indexed.finalizedBalance(depA, "0xpaid")).thenReturn(new BigDecimal("30"));
        when(indexed.finalizedBalance(depB, "0xpaid")).thenReturn(BigDecimal.ZERO);

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN", "§26", "CA", UUID.randomUUID(), caId));

        verify(tokenAdminPort).forceBurn(eq(depA), eq("0xpaid"), eq(BigInteger.valueOf(30)), any(), any(), any());
        ArgumentCaptor<AssetRedemptionIncompleteEvent> captor = ArgumentCaptor.forClass(AssetRedemptionIncompleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().payload().toString()).contains("BALANCE_NOT_FOUND_ON_DEPLOYMENTS: 70");
    }

    // ── idempotency and failure ───────────────────────────────────────────────

    private AssetRedemptionBurn row(UUID assetId, UUID deploymentId, String wallet, AssetRedemptionBurn.Status status) {
        AssetRedemptionBurn b = new AssetRedemptionBurn();
        b.setAssetId(assetId);
        b.setDeploymentId(deploymentId);
        b.setWalletAddress(wallet);
        b.setAmount(new BigDecimal("500"));
        b.setTxId(UUID.randomUUID());
        b.setStatus(status);
        table.add(b);
        return b;
    }

    @Test
    @DisplayName("C7: a redelivered redemption event never burns a wallet twice (idempotent by asset + deployment + wallet)")
    void redeliveryDoesNotBurnTwice() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        givenErc20(assetId, deployment(deploymentId));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(holder("0xaaa", new BigDecimal("500"))));
        when(tokenAdminPort.forceBurn(any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        listener.onAssetRedeemed(event(assetId));
        listener.onAssetRedeemed(event(assetId));

        verify(tokenAdminPort, times(1)).forceBurn(any(), any(), any(), any(), any(), any());
        assertThat(table).hasSize(1);
    }

    @Test
    @DisplayName("C7: resuming re-dispatches only the burns that FAILED; submitted and confirmed ones are never burnt again")
    void resumeRedrivesOnlyFailedBurns() {
        UUID assetId = UUID.randomUUID();
        UUID depA = UUID.randomUUID();
        UUID depB = UUID.randomUUID();
        UUID caId = UUID.randomUUID();
        bondWith(assetId, caId, "0xpaid", "100", "100", deployment(depA), deployment(depB));
        when(indexed.finalizedBalance(depA, "0xpaid")).thenReturn(new BigDecimal("60"));
        when(indexed.finalizedBalance(depB, "0xpaid")).thenReturn(new BigDecimal("90"));
        AssetRedemptionBurn confirmed = row(assetId, depA, "0xpaid", AssetRedemptionBurn.Status.CONFIRMED);
        AssetRedemptionBurn failed = row(assetId, depB, "0xpaid", AssetRedemptionBurn.Status.FAILED);
        failed.setFailureReason("burn 0xold FAILED: Transaction reverted on-chain");
        UUID retryTx = UUID.randomUUID();
        when(tokenAdminPort.forceBurn(eq(depB), eq("0xpaid"), eq(BigInteger.valueOf(40)), any(), any(), any())).thenReturn(retryTx);

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN", "§26", "CA", UUID.randomUUID(), caId));

        verify(tokenAdminPort, never()).forceBurn(eq(depA), any(), any(), any(), any(), any());
        verify(tokenAdminPort, times(1)).forceBurn(eq(depB), eq("0xpaid"), eq(BigInteger.valueOf(40)), any(), any(), any());
        assertThat(failed.getStatus()).isEqualTo(AssetRedemptionBurn.Status.SUBMITTED);
        assertThat(failed.getTxId()).isEqualTo(retryTx);
        assertThat(failed.getFailureReason()).isNull();
        assertThat(confirmed.getStatus()).isEqualTo(AssetRedemptionBurn.Status.CONFIRMED);
    }

    @Test
    @DisplayName("C7: a burn that cannot be submitted is recorded FAILED and reported - the asset is never completed over it")
    void submissionFailureIsRecordedAndReported() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        givenErc20(assetId, deployment(deploymentId));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(holder("0xaaa", new BigDecimal("500"))));
        when(tokenAdminPort.forceBurn(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("wallet has an active Sperrvermerk"));

        listener.onAssetRedeemed(event(assetId));

        assertThat(table).singleElement().satisfies(b -> {
            assertThat(b.getStatus()).isEqualTo(AssetRedemptionBurn.Status.FAILED);
            assertThat(b.getFailureReason()).contains("Sperrvermerk");
        });
        ArgumentCaptor<AssetRedemptionIncompleteEvent> captor = ArgumentCaptor.forClass(AssetRedemptionIncompleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().payload().toString()).contains("BURN_FAILED");
        verify(lifecycle, never()).completeRedemption(any(), anyInt());
    }

    @Test
    @DisplayName("C7: nothing to burn at all (every burn already confirmed on a redelivery) completes the redemption")
    void completesWhenEveryBurnIsAlreadyConfirmed() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        givenErc20(assetId, deployment(deploymentId));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(holder("0xaaa", new BigDecimal("500"))));
        row(assetId, deploymentId, "0xaaa", AssetRedemptionBurn.Status.CONFIRMED);

        listener.onAssetRedeemed(event(assetId));

        verify(tokenAdminPort, never()).forceBurn(any(), any(), any(), any(), any(), any());
        verify(lifecycle).completeRedemption(assetId, 1);
    }

    @Test
    @DisplayName("non-bond redemption with every holder burnt publishes no incomplete event")
    void noIncompleteEventWhenAllBurnt() {
        UUID assetId = UUID.randomUUID();
        givenErc20(assetId, deployment(UUID.randomUUID()));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(holder("0xaaa", BigDecimal.ONE)));
        when(tokenAdminPort.forceBurn(any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        listener.onAssetRedeemed(event(assetId));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(redemptionReadiness, never()).settledRetirementAction(any());
    }
}
