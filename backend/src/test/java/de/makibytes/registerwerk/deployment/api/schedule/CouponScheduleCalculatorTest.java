package de.makibytes.registerwerk.deployment.api.schedule;

import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CouponScheduleCalculatorTest {

    private static final Target2Calendar T2 = Target2Calendar.INSTANCE;

    private static CouponScheduleCalculator.Terms terms(LocalDate issue, LocalDate maturity, PaymentFrequency f,
                                                        DayCountConvention dc) {
        return new CouponScheduleCalculator.Terms(issue, maturity, f, dc, StubRule.SHORT_FIRST,
                BusinessDayConvention.MODIFIED_FOLLOWING, T2, 1, 5);
    }

    /** Golden vector: 4 % ACT/ACT-ICMA annual, 15 Jan 2026 → 15 Jan 2029 on TARGET2. */
    @Test
    void annualIcmaTarget2GoldenVector() {
        List<CouponPeriod> periods = CouponScheduleCalculator.generate(terms(
                LocalDate.of(2026, 1, 15), LocalDate.of(2029, 1, 15), PaymentFrequency.ANNUAL,
                DayCountConvention.ACT_ACT_ICMA));

        assertThat(periods).hasSize(3);
        assertThat(periods).extracting(CouponPeriod::periodEnd).containsExactly(
                LocalDate.of(2027, 1, 15), LocalDate.of(2028, 1, 15), LocalDate.of(2029, 1, 15));
        // 15 Jan 2028 is a Saturday → Modified Following → Monday 17 Jan 2028.
        assertThat(periods).extracting(CouponPeriod::paymentDate).containsExactly(
                LocalDate.of(2027, 1, 15), LocalDate.of(2028, 1, 17), LocalDate.of(2029, 1, 15));
        CouponPeriod second = periods.get(1);
        assertThat(second.recordDate()).isEqualTo(LocalDate.of(2028, 1, 14));       // payment − 1 TARGET day
        assertThat(second.announcementDate()).isEqualTo(LocalDate.of(2028, 1, 7));  // record − 5 TARGET days
        assertThat(periods).allSatisfy(p -> {
            assertThat(p.stub()).isFalse();
            assertThat(p.dayCountFraction()).isEqualByComparingTo("1");
            // 1 000 face × 4 % × 1 = 40.00 per unit
            assertThat(new BigDecimal("1000").multiply(new BigDecimal("0.04")).multiply(p.dayCountFraction()))
                    .isEqualByComparingTo("40");
        });
        assertThat(periods.get(0).periodStart()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void target2EasterHolidays2027And2028() {
        assertThat(Target2Calendar.easterSunday(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
        assertThat(Target2Calendar.easterSunday(2028)).isEqualTo(LocalDate.of(2028, 4, 16));
        assertThat(T2.isBusinessDay(LocalDate.of(2027, 3, 26))).isFalse(); // Good Friday
        assertThat(T2.isBusinessDay(LocalDate.of(2027, 3, 29))).isFalse(); // Easter Monday
        assertThat(T2.isBusinessDay(LocalDate.of(2028, 4, 14))).isFalse();
        assertThat(T2.isBusinessDay(LocalDate.of(2028, 4, 17))).isFalse();
        assertThat(T2.isBusinessDay(LocalDate.of(2028, 4, 18))).isTrue();
        assertThat(T2.isBusinessDay(LocalDate.of(2027, 5, 1))).isFalse();
        assertThat(T2.isBusinessDay(LocalDate.of(2026, 12, 25))).isFalse();
        assertThat(T2.isBusinessDay(LocalDate.of(2026, 12, 28))).isTrue();
        // A coupon due on Good Friday 2028 is paid on the Tuesday after Easter Monday.
        assertThat(T2.adjust(LocalDate.of(2028, 4, 14), BusinessDayConvention.MODIFIED_FOLLOWING))
                .isEqualTo(LocalDate.of(2028, 4, 18));
    }

    @Test
    void modifiedFollowingDoesNotCrossMonthEnd() {
        // Saturday 31 Aug 2024: Following would be 2 Sep → Modified Following rolls back to Fri 30 Aug.
        assertThat(T2.adjust(LocalDate.of(2024, 8, 31), BusinessDayConvention.MODIFIED_FOLLOWING))
                .isEqualTo(LocalDate.of(2024, 8, 30));
        assertThat(T2.adjust(LocalDate.of(2024, 8, 31), BusinessDayConvention.FOLLOWING))
                .isEqualTo(LocalDate.of(2024, 9, 2));
        assertThat(T2.adjust(LocalDate.of(2024, 8, 31), BusinessDayConvention.NONE))
                .isEqualTo(LocalDate.of(2024, 8, 31));
    }

    @Test
    void shortFirstStubRolledBackFromMaturity() {
        List<CouponPeriod> periods = CouponScheduleCalculator.generate(terms(
                LocalDate.of(2026, 3, 1), LocalDate.of(2029, 1, 15), PaymentFrequency.ANNUAL,
                DayCountConvention.ACT_ACT_ICMA));

        assertThat(periods).hasSize(3);
        CouponPeriod stub = periods.get(0);
        assertThat(stub.stub()).isTrue();
        assertThat(stub.periodStart()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(stub.periodEnd()).isEqualTo(LocalDate.of(2027, 1, 15));
        // 320 accrued days / (1 × 365 days in the notional period 15 Jan 2026 → 15 Jan 2027)
        assertThat(stub.dayCountFraction()).isEqualByComparingTo(
                new BigDecimal("320").divide(new BigDecimal("365"), 18, RoundingMode.HALF_EVEN));
        assertThat(periods.get(1).stub()).isFalse();
        assertThat(periods.get(1).dayCountFraction()).isEqualByComparingTo("1");
    }

    @Test
    void semiAnnualEndOfMonthRule() {
        List<CouponPeriod> periods = CouponScheduleCalculator.generate(terms(
                LocalDate.of(2026, 2, 28), LocalDate.of(2028, 8, 31), PaymentFrequency.SEMI_ANNUAL,
                DayCountConvention.ACT_ACT_ICMA));

        assertThat(periods).extracting(CouponPeriod::periodEnd).containsExactly(
                LocalDate.of(2026, 8, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 8, 31),
                LocalDate.of(2028, 2, 29), LocalDate.of(2028, 8, 31));
        assertThat(periods.get(0).stub()).isFalse();
        assertThat(periods).allSatisfy(p -> assertThat(p.dayCountFraction()).isEqualByComparingTo("0.5"));
    }

    @Test
    void zeroCouponHasNoPeriods() {
        assertThat(CouponScheduleCalculator.generate(terms(LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1),
                PaymentFrequency.ZERO, DayCountConvention.ACT_360))).isEmpty();
    }

    @Test
    void otherDayCountConventions() {
        LocalDate s = LocalDate.of(2026, 1, 31);
        LocalDate e = LocalDate.of(2026, 7, 31);
        assertThat(DayCountFractions.fraction(DayCountConvention.ACT_360, s, e, s, 2))
                .isEqualByComparingTo(new BigDecimal("181").divide(new BigDecimal("360"), 18, RoundingMode.HALF_EVEN));
        assertThat(DayCountFractions.fraction(DayCountConvention.ACT_365, s, e, s, 2))
                .isEqualByComparingTo(new BigDecimal("181").divide(new BigDecimal("365"), 18, RoundingMode.HALF_EVEN));
        assertThat(DayCountFractions.fraction(DayCountConvention.THIRTY_360, s, e, s, 2)).isEqualByComparingTo("0.5");
        assertThat(DayCountFractions.fraction(DayCountConvention.THIRTY_E_360, s, e, s, 2)).isEqualByComparingTo("0.5");
        // 30/360 US vs 30E/360 differ when only the end date is the 31st.
        LocalDate s2 = LocalDate.of(2026, 1, 15);
        assertThat(DayCountFractions.fraction(DayCountConvention.THIRTY_360, s2, e, s2, 2))
                .isEqualByComparingTo(new BigDecimal("196").divide(new BigDecimal("360"), 18, RoundingMode.HALF_EVEN));
        assertThat(DayCountFractions.fraction(DayCountConvention.THIRTY_E_360, s2, e, s2, 2))
                .isEqualByComparingTo(new BigDecimal("195").divide(new BigDecimal("360"), 18, RoundingMode.HALF_EVEN));
    }
}
