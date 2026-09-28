package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.shared.RegisterClock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** Shared fixtures for the corporate-action unit tests. */
final class CorporateActionTestSupport {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private CorporateActionTestSupport() {}

    /** Register clock in the JVM's zone — "today" equals {@code LocalDate.now()} in the tests. */
    static RegisterClock systemRegisterClock() {
        return new RegisterClock(Clock.systemDefaultZone(), ZoneId.systemDefault());
    }

    /** Register clock pinned to noon (Berlin) of {@code today}. */
    static RegisterClock registerClockAt(LocalDate today) {
        Instant noon = today.atTime(12, 0).atZone(BERLIN).toInstant();
        return new RegisterClock(Clock.fixed(noon, BERLIN), BERLIN);
    }

    /** The resolver outcome the snapshot would get for these rows, taken at their current nominal. */
    static RecordDatePositionResolver.Resolution positionsOf(List<AssetHolder> holders) {
        return new RecordDatePositionResolver.Resolution(holders.stream()
                .map(h -> new RecordDatePositionResolver.Position(h.getId(), h.getInvestorId(), h.getWalletAddress(),
                        h.getHolderKind(), h.getNominalAmount() != null ? h.getNominalAmount() : BigDecimal.ZERO))
                .toList(), Optional.empty(), List.of());
    }
}
