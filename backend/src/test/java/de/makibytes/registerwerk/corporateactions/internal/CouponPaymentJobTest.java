package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the idempotency guard of the coupon job: a SCHEDULED payment whose
 * corporate action already exists (settlement still confirming asynchronously)
 * must not trigger a second action — that would pay the coupon to all holders twice.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CouponPaymentJob idempotency unit tests")
class CouponPaymentJobTest {

    @Mock
    private AssetCouponPaymentRepository couponPaymentRepository;

    @Mock
    private CorporateActionRepository corporateActionRepository;

    @Mock
    private CorporateActionService corporateActionService;

    @Mock
    private AssetBondTermsRepository bondTermsRepository;

    @Mock
    private AssetRepository assetRepository;

    private CouponPaymentJob job;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        job = new CouponPaymentJob(couponPaymentRepository, corporateActionRepository, corporateActionService,
                bondTermsRepository, assetRepository, CorporateActionTestSupport.systemRegisterClock());
    }

    private AssetCouponPayment duePayment() {
        AssetCouponPayment payment = new AssetCouponPayment();
        // AssetCouponPayment has no setId (DB-generated) — pin it for the mock via reflection.
        org.springframework.test.util.ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setAssetId(UUID.randomUUID());
        payment.setScheduledDate(LocalDate.now().plusDays(6));
        payment.setRecordDate(LocalDate.now().plusDays(5));
        payment.setAnnouncementDate(LocalDate.now());
        payment.setCouponStatus(CouponStatus.SCHEDULED);
        payment.setAmountPerUnit(new java.math.BigDecimal("40"));
        return payment;
    }

    @Test
    @DisplayName("a due floating-rate coupon without a fixing (null amount) is not raised")
    void floatingCouponAwaitingFixing_isNotRaised() {
        AssetCouponPayment payment = duePayment();
        payment.setAmountPerUnit(null);
        when(couponPaymentRepository.findAnnounceable(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(payment));
        when(corporateActionRepository.existsByCouponPaymentId(payment.getId())).thenReturn(false);

        job.processDuePayments();

        verify(corporateActionService, org.mockito.Mockito.never()).announce(any(CorporateAction.class));
    }

    @Test
    @DisplayName("first run creates a corporate action for a due payment")
    void firstRun_createsAction() throws Exception {
        AssetCouponPayment payment = duePayment();
        when(couponPaymentRepository.findAnnounceable(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(payment));
        when(corporateActionRepository.existsByCouponPaymentId(payment.getId())).thenReturn(false);

        job.processDuePayments();

        verify(corporateActionService).announce(any(CorporateAction.class));
    }

    @Test
    @DisplayName("re-run while settlement is pending does NOT create a duplicate action")
    void rerun_skipsAlreadyProcessedPayment() throws Exception {
        AssetCouponPayment payment = duePayment();
        when(couponPaymentRepository.findAnnounceable(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(payment));
        when(corporateActionRepository.existsByCouponPaymentId(payment.getId())).thenReturn(true);

        job.processDuePayments();

        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("due payment for an asset whose register was transferred to a successor operator does not create an action")
    void duePayment_skipsActionWhenAssetTransferredOut() throws Exception {
        AssetCouponPayment payment = duePayment();
        Asset transferredAsset = new Asset();
        transferredAsset.setStatus(AssetStatus.TRANSFERRED_OUT);

        when(couponPaymentRepository.findAnnounceable(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(payment));
        when(corporateActionRepository.existsByCouponPaymentId(payment.getId())).thenReturn(false);
        when(assetRepository.findById(payment.getAssetId())).thenReturn(Optional.of(transferredAsset));

        job.processDuePayments();

        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("T3-05: raised at the announcement date — record/payment dates come from the schedule row, not 'today'")
    void raisesAtAnnouncementDate() {
        AssetCouponPayment payment = duePayment();
        when(couponPaymentRepository.findAnnounceable(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(payment));

        job.processDuePayments();

        org.mockito.ArgumentCaptor<CorporateAction> captor = org.mockito.ArgumentCaptor.forClass(CorporateAction.class);
        verify(corporateActionService).announce(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getRecordDate()).isEqualTo(LocalDate.now().plusDays(5));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPaymentDate()).isEqualTo(LocalDate.now().plusDays(6));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getAnnouncementDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("T3-05: a legacy row without dates gets them derived and is only raised once its announcement date is reached")
    void legacyRowGetsDerivedDates() {
        AssetCouponPayment legacy = duePayment();
        legacy.setAnnouncementDate(null);
        legacy.setRecordDate(null);
        legacy.setScheduledDate(LocalDate.now().plusDays(60)); // announcement (~7 business days before) still ahead
        when(couponPaymentRepository.findAnnounceable(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(legacy));

        job.processDuePayments();
        verify(corporateActionService, never()).announce(any());

        legacy.setScheduledDate(LocalDate.now().plusDays(3)); // announcement date derived in the past
        job.processDuePayments();
        verify(corporateActionService).announce(any(CorporateAction.class));
        org.assertj.core.api.Assertions.assertThat(legacy.getAnnouncementDate()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(legacy.getRecordDate()).isBefore(legacy.getScheduledDate());
    }
}
