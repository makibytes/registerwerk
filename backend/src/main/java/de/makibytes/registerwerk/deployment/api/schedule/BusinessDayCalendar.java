package de.makibytes.registerwerk.deployment.api.schedule;

import java.time.LocalDate;

/** A holiday calendar: which days are business days, plus the adjustment arithmetic on top. */
public interface BusinessDayCalendar {

    boolean isBusinessDay(LocalDate date);

    /** Moves {@code date} by {@code days} business days (negative = backwards); 0 returns it unchanged. */
    default LocalDate addBusinessDays(LocalDate date, int days) {
        LocalDate d = date;
        int step = days >= 0 ? 1 : -1;
        for (int remaining = Math.abs(days); remaining > 0; ) {
            d = d.plusDays(step);
            if (isBusinessDay(d)) {
                remaining--;
            }
        }
        return d;
    }

    /** Adjusts a scheduled date that may fall on a non-business day. */
    default LocalDate adjust(LocalDate date, BusinessDayConvention convention) {
        if (convention == null || convention == BusinessDayConvention.NONE || isBusinessDay(date)) {
            return date;
        }
        return switch (convention) {
            case FOLLOWING -> nextBusinessDay(date);
            case PRECEDING -> previousBusinessDay(date);
            case MODIFIED_FOLLOWING -> {
                LocalDate following = nextBusinessDay(date);
                yield following.getMonth() == date.getMonth() ? following : previousBusinessDay(date);
            }
            case NONE -> date;
        };
    }

    private LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (!isBusinessDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    private LocalDate previousBusinessDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        while (!isBusinessDay(d)) {
            d = d.minusDays(1);
        }
        return d;
    }
}
