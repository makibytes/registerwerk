package de.makibytes.registerwerk.shared;

import java.time.Clock;
import java.time.ZoneId;

/** A {@link RegisterClock} on the JVM's own zone, for unit tests that only need "today" to be today. */
public final class TestRegisterClock {

    private TestRegisterClock() {
    }

    public static RegisterClock systemDefault() {
        return new RegisterClock(Clock.systemDefaultZone(), ZoneId.systemDefault());
    }
}
