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
import org.mockito.InjectMocks;
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

    @InjectMocks
    private CouponPaymentJob job;

    private AssetCouponPayment duePayment() {
        AssetCouponPayment payment = new AssetCouponPayment();
        // AssetCouponPayment has no setId (DB-generated) — pin it for the mock via reflection.
        org.springframework.test.util.ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setAssetId(UUID.randomUUID());
        payment.setScheduledDate(LocalDate.now().minusDays(1));
        payment.setCouponStatus(CouponStatus.SCHEDULED);
        return payment;
    }

    @Test
    @DisplayName("first run creates a corporate action for a due payment")
    void firstRun_createsAction() throws Exception {
        AssetCouponPayment payment = duePayment();
        when(couponPaymentRepository.findByCouponStatusAndScheduledDateLessThanEqual(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class))).thenReturn(List.of(payment));
        when(corporateActionRepository.existsByCouponPaymentId(payment.getId())).thenReturn(false);

        job.execute(null);

        verify(corporateActionService).announce(any(CorporateAction.class));
    }

    @Test
    @DisplayName("re-run while settlement is pending does NOT create a duplicate action")
    void rerun_skipsAlreadyProcessedPayment() throws Exception {
        AssetCouponPayment payment = duePayment();
        when(couponPaymentRepository.findByCouponStatusAndScheduledDateLessThanEqual(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class))).thenReturn(List.of(payment));
        when(corporateActionRepository.existsByCouponPaymentId(payment.getId())).thenReturn(true);

        job.execute(null);

        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("due payment for an asset whose register was transferred to a successor operator does not create an action")
    void duePayment_skipsActionWhenAssetTransferredOut() throws Exception {
        AssetCouponPayment payment = duePayment();
        Asset transferredAsset = new Asset();
        transferredAsset.setStatus(AssetStatus.TRANSFERRED_OUT);

        when(couponPaymentRepository.findByCouponStatusAndScheduledDateLessThanEqual(
                eq(CouponStatus.SCHEDULED), any(LocalDate.class))).thenReturn(List.of(payment));
        when(corporateActionRepository.existsByCouponPaymentId(payment.getId())).thenReturn(false);
        when(assetRepository.findById(payment.getAssetId())).thenReturn(Optional.of(transferredAsset));

        job.execute(null);

        verify(corporateActionService, never()).announce(any());
    }
}
