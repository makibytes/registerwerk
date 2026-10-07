package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSnapshotBlockedEvent;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 0b C5 through the REAL {@link RegisterFreshnessGate}: a coupon / redemption / dividend whose asset has a
 * deployment that does not count in whole units must not be computed ({@code amountPerUnit x nominal} would be wrong
 * by 10^decimals). It parks visibly as SNAPSHOT_BLOCKED (audited event, metric, alert) instead of paying a wrong
 * amount or failing in a log line.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Corporate-action computation refuses non-whole-unit deployments (C5)")
class CorporateActionRegisterUnitsTest {

    @Mock private CorporateActionRepository repository;
    @Mock private CorporateActionEntryRepository entryRepository;
    @Mock private RecordDatePositionResolver positionResolver;
    @Mock private de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository bondTermsRepository;
    @Mock private CorporateActionSettlementWriter settlementWriter;
    @Mock private AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private CorporateActionProposalValidator proposalValidator;
    @Mock private ApplicationEventPublisher events;
    @Mock private PartyEligibilityGate partyGate;
    @Mock private EntityTaskPort entityTasks;
    @Mock private FinalityGate finalityGate;
    @Mock private AssetRepository assetRepository;
    @Mock private AssetDeploymentRepository deploymentRepository;

    private CorporateActionService service;
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RegisterFreshnessGate gate = new RegisterFreshnessGate(assetRepository, deploymentRepository, repository,
                new SimpleMeterRegistry(), CorporateActionTestSupport.systemRegisterClock(), Duration.ZERO,
                org.mockito.Mockito.mock(de.makibytes.registerwerk.indexer.api.IndexedChainTime.class));
        service = new CorporateActionService(repository, entryRepository, positionResolver, settlementWriter,
                couponPaymentRepository, proposalValidator, events, partyGate, entityTasks, finalityGate, gate,
                bondTermsRepository, CorporateActionTestSupport.systemRegisterClock(),
                CorporateActionTestSupport.directTransactions());
        Asset asset = new Asset();
        asset.setId(assetId);
        // reconciled long after any record date, so only the units guard can refuse
        ReflectionTestUtils.setField(asset, "lastSuccessfulHolderSyncAt", Instant.now().plusSeconds(3600));
        lenient().when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
    }

    private static AssetDeployment deployment(UUID assetId, Integer decimals) {
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setAssetId(assetId);
        d.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        d.setTokenDecimals(decimals);
        return d;
    }

    private CorporateAction dueCoupon() {
        CorporateAction ca = new CorporateAction();
        ReflectionTestUtils.setField(ca, "id", UUID.randomUUID());
        ca.setAssetId(assetId);
        ca.setActionType(CorporateAction.ActionType.COUPON);
        ca.setStatus(CorporateAction.Status.ANNOUNCED);
        ca.setAmountPerUnit(new BigDecimal("0.05"));
        ca.setCurrency("EUR");
        ca.setRecordDate(LocalDate.now().minusDays(1));
        ca.setPaymentDate(LocalDate.now().plusDays(5));
        when(repository.findById(ca.getId())).thenReturn(Optional.of(ca));
        when(repository.findReadyToCompute(any())).thenReturn(List.of(ca));
        when(repository.findDueForSettlement(any())).thenReturn(List.of());
        when(repository.findByStatus(CorporateAction.Status.SETTLED)).thenReturn(List.of());
        return ca;
    }

    @Test
    @DisplayName("an 18-decimals deployment parks the coupon as SNAPSHOT_BLOCKED (audited), no entry, no total")
    void eighteenDecimalsBlocksTheSnapshot() {
        CorporateAction ca = dueCoupon();
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(assetId, 18)));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.SNAPSHOT_BLOCKED);
        assertThat(ca.getSnapshotBlockedReason()).contains("decimals=18").contains("whole");
        assertThat(ca.getTotalAmount()).isNull();
        verify(entryRepository, never()).save(any(CorporateActionEntry.class));
        ArgumentCaptor<CorporateActionSnapshotBlockedEvent> event =
                ArgumentCaptor.forClass(CorporateActionSnapshotBlockedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().reason()).contains("decimals=18");
    }

    @Test
    @DisplayName("a deployment of unknown decimals is refused too (fail closed)")
    void unknownDecimalsBlocksTheSnapshot() {
        CorporateAction ca = dueCoupon();
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(assetId, null)));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.SNAPSHOT_BLOCKED);
        assertThat(ca.getSnapshotBlockedReason()).contains("unknown");
    }

    @Test
    @DisplayName("a whole-unit (decimals=0) deployment is computed as before")
    void wholeUnitDeploymentIsComputed() {
        CorporateAction ca = dueCoupon();
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(assetId, 0)));
        AssetHolder holder = new AssetHolder();
        ReflectionTestUtils.setField(holder, "id", UUID.randomUUID());
        holder.setInvestorId(UUID.randomUUID());
        holder.setWalletAddress("0xabc");
        holder.setNominalAmount(new BigDecimal("1000"));
        when(entryRepository.existsByCorporateActionId(ca.getId())).thenReturn(false);
        when(positionResolver.resolve(any(), any())).thenReturn(CorporateActionTestSupport.positionsOf(List.of(holder)));

        service.processDailyTransitions();

        assertThat(ca.getStatus()).isEqualTo(CorporateAction.Status.COMPUTED);
        assertThat(ca.getTotalAmount()).isEqualByComparingTo("50");
    }
}
