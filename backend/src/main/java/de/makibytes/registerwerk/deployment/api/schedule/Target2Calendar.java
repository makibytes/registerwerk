package de.makibytes.registerwerk.deployment.api.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;

/**
 * The TARGET2 (T2) closing-day calendar of the Eurosystem: Saturdays, Sundays, New Year's Day,
 * Good Friday, Easter Monday, 1 May (Labour Day), 25 and 26 December. This is the standard
 * business-day calendar for EUR-denominated bond payments.
 */
public final class Target2Calendar implements BusinessDayCalendar {

    public static final Target2Calendar INSTANCE = new Target2Calendar();

    private Target2Calendar() {}

    @Override
    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        Month m = date.getMonth();
        int d = date.getDayOfMonth();
        if ((m == Month.JANUARY && d == 1) || (m == Month.MAY && d == 1)
                || (m == Month.DECEMBER && (d == 25 || d == 26))) {
            return false;
        }
        LocalDate easter = easterSunday(date.getYear());
        return !date.equals(easter.minusDays(2)) && !date.equals(easter.plusDays(1));
    }

    /** Gregorian Easter Sunday (anonymous Gregorian algorithm — Meeus/Jones/Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
