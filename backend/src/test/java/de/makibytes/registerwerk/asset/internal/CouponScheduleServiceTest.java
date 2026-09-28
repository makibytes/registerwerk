package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.CouponPaymentActionLookup;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPayment;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;
import de.makibytes.registerwerk.shared.RegisterClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CouponScheduleService.regenerate")
class CouponScheduleServiceTest {

    private final UUID assetId = UUID.randomUUID();
    private final AssetBondTermsRepository termsRepo = mock(AssetBondTermsRepository.class);
    private final AssetCouponPaymentRepository couponRepo = mock(AssetCouponPaymentRepository.class);
    private final CouponPaymentActionLookup actions = mock(CouponPaymentActionLookup.class);

    private CouponScheduleService service(Instant now) {
        RegisterClock rc = new RegisterClock(Clock.fixed(now, ZoneOffset.UTC), ZoneId.of("Europe/Berlin"));
        return new CouponScheduleService(termsRepo, couponRepo, actions, mock(ApplicationEventPublisher.class),
                rc, new SimpleMeterRegistry());
    }

    private AssetBondTerms terms(String issue, String maturity, String rate) {
        AssetBondTerms t = new AssetBondTerms();
        t.setAssetId(assetId);
        t.setFaceValue(new BigDecimal("1000"));
        t.setIssueDate(LocalDate.parse(issue));
        t.setMaturityDate(LocalDate.parse(maturity));
        t.setCouponRate(new BigDecimal(rate));
        t.setPaymentFrequency(PaymentFrequency.ANNUAL);
        return t;
    }

    private AssetCouponPayment row(int no, LocalDate scheduled, LocalDate periodEnd, CouponStatus status) {
        AssetCouponPayment r = new AssetCouponPayment();
        r.setAssetId(assetId);
        r.setPeriodNo(no);
        r.setScheduledDate(scheduled);
        r.setPeriodEnd(periodEnd);
        r.setCouponStatus(status);
        return r;
    }

    @Test
    @DisplayName("today is the register-zone date: a row due on the register-zone 'yesterday' is not replaced at 00:30 CET")
    void usesRegisterZoneForBoundary() {
        // 2026-12-31T23:30Z is already 2027-01-01 in Berlin; a SCHEDULED row due 2026-12-31 is past.
        AssetCouponPayment due = row(1, LocalDate.parse("2026-12-31"), LocalDate.parse("2026-12-31"), CouponStatus.SCHEDULED);
        when(termsRepo.findById(assetId)).thenReturn(java.util.Optional.of(terms("2026-01-01", "2030-01-01", "0")));
        when(couponRepo.findByAssetIdOrderByPeriodNo(assetId)).thenReturn(List.of(due));

        int written = service(Instant.parse("2026-12-31T23:30:00Z")).regenerate(assetId, null, "SYSTEM", "TEST");

        assertThat(written).isZero();
        verify(couponRepo, never()).deleteAll(any());
    }

    @Test
    @DisplayName("new rows are renumbered behind kept rows so period numbers never collide")
    void renumbersOnCollisionWithKeptRow() {
        AssetCouponPayment paid = row(2, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-01"), CouponStatus.PAID);
        when(termsRepo.findById(assetId)).thenReturn(java.util.Optional.of(terms("2025-06-01", "2029-06-01", "0.04")));
        when(couponRepo.findByAssetIdOrderByPeriodNo(assetId)).thenReturn(List.of(paid));

        service(Instant.parse("2026-09-29T10:00:00Z")).regenerate(assetId, null, "SYSTEM", "TEST");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssetCouponPayment>> captor = ArgumentCaptor.forClass(List.class);
        verify(couponRepo).saveAll(captor.capture());
        List<Integer> numbers = captor.getValue().stream().map(AssetCouponPayment::getPeriodNo).toList();
        assertThat(numbers).isNotEmpty().doesNotContain(2).allMatch(n -> n > 2).doesNotHaveDuplicates();
    }
}
