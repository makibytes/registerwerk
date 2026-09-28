package de.makibytes.registerwerk.deployment.api.schedule;

/** Persistable identifier of a {@link BusinessDayCalendar}. Only TARGET2 is supported for now. */
public enum HolidayCalendar {
    TARGET2(Target2Calendar.INSTANCE);

    private final BusinessDayCalendar calendar;

    HolidayCalendar(BusinessDayCalendar calendar) {
        this.calendar = calendar;
    }

    public BusinessDayCalendar calendar() {
        return calendar;
    }
}
