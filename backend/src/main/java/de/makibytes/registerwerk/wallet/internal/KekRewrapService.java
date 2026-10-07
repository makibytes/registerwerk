package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.EnvelopeSecretInventory;
import de.makibytes.registerwerk.shared.EnvelopeSecretInventory.RewrapOutcome;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import de.makibytes.registerwerk.wallet.events.KekRewrapCompletedEvent;
import de.makibytes.registerwerk.wallet.events.KekVersionRetiredEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps every envelope-encrypted secret on the active KEK version and guards retirement of old versions.
 *
 * <p>The platform KEK wraps wallet keystore data keys, operator TOTP secrets, webhook signing secrets, Travel
 * Rule peer keys and (in flight) secure-link seals. Each module contributes an {@link EnvelopeSecretInventory};
 * this service aggregates them, so retiring a KEK version is refused while <em>any</em> ciphertext still
 * references it, and one re-wrap run (endpoint or nightly job) moves them all. Only the wrapped data keys are
 * touched; no secret is decrypted, returned or logged.
 */
@Service
public class KekRewrapService {

    private static final Logger log = LoggerFactory.getLogger(KekRewrapService.class);
    private static final String LOCK_NAME = "kekRewrap";

    public record RewrapReport(Map<String, RewrapOutcome> byType) {
        public int rewrapped() { return byType.values().stream().mapToInt(RewrapOutcome::rewrapped).sum(); }
        public int failed() { return byType.values().stream().mapToInt(RewrapOutcome::failed).sum(); }
    }

    public record RetireResult(String version, boolean disabledInProcess) { }

    private final List<EnvelopeSecretInventory> inventories;
    private final KekProvider kek;
    private final LockProvider lockProvider;
    private final ApplicationEventPublisher events;
    private final KekRewrapProperties properties;
    private final Map<String, Counter> failures = new ConcurrentHashMap<>();
    /** type -> ciphertexts not on the active version, as of the last successful scan. */
    private final Map<String, Long> onOldVersion = new ConcurrentHashMap<>();

    KekRewrapService(List<EnvelopeSecretInventory> inventories, KekProvider kek, LockProvider lockProvider,
                     MeterRegistry registry, ApplicationEventPublisher events, KekRewrapProperties properties) {
        this.inventories = inventories;
        this.kek = kek;
        this.lockProvider = lockProvider;
        this.events = events;
        this.properties = properties;
        for (EnvelopeSecretInventory inv : inventories) {
            String type = inv.type();
            Gauge.builder("registerwerk.kek.secrets.on.old.version", () -> onOldVersion.getOrDefault(type, 0L))
                    .description("Envelope-encrypted secrets whose data key is wrapped by a KEK version other than the active one")
                    .tag("type", type).register(registry);
            failures.put(type, Counter.builder("registerwerk.kek.rewrap.failures")
                    .description("Secrets that could not be re-wrapped onto the active KEK version")
                    .tag("type", type).register(registry));
        }
    }

    /** Ciphertext count per type and KEK version label; throws if any inventory cannot be read. */
    public Map<String, Map<String, Long>> scan() {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (EnvelopeSecretInventory inv : inventories) {
            result.put(inv.type(), inv.countByKekVersion());
        }
        result.forEach(this::remember);
        return result;
    }

    public Optional<String> activeVersion() {
        return kek.activeVersion();
    }

    /** Re-wraps every stale secret of every type. At most one run at a time across the cluster. */
    public RewrapReport rewrapAll(UUID actorId, String actorRole) {
        return rewrapAll(actorId, actorRole, null);
    }

    public RewrapReport rewrapAll(UUID actorId, String actorRole, UUID approverId) {
        RewrapReport report = locked(this::runRewrap);
        events.publishEvent(completedEvent(actorId, actorRole, approverId, report));
        return report;
    }

    /**
     * Refuses (409) while any ciphertext references {@code version}; otherwise stops using it in this process
     * where the provider allows that and audits the retirement. For cloud providers the operator disables the
     * version in the provider console afterwards - this is the check to do first.
     */
    public RetireResult retireVersion(String version, UUID actorId, String actorRole) {
        return retireVersion(version, actorId, actorRole, null);
    }

    public RetireResult retireVersion(String version, UUID actorId, String actorRole, UUID approverId) {
        Optional<String> active = kek.activeVersion();
        if (active.isEmpty()) {
            throw new InvalidStateTransitionException("The configured KEK provider does not expose key versions");
        }
        if (active.get().equals(version)) {
            throw new InvalidStateTransitionException(
                    "KEK version '" + version + "' is the active version and cannot be retired");
        }
        Map<String, Long> referencing = new TreeMap<>();
        for (EnvelopeSecretInventory inv : inventories) {
            try {
                long n = inv.countByKekVersion().getOrDefault(version, 0L);
                if (n > 0) {
                    referencing.put(inv.type(), n);
                }
            } catch (RuntimeException e) {
                throw new InvalidStateTransitionException("KEK inventory for " + inv.type()
                        + " is unavailable; cannot verify that version '" + version + "' is unused");
            }
        }
        if (!referencing.isEmpty()) {
            throw new InvalidStateTransitionException("KEK version '" + version + "' is still referenced by "
                    + referencing.toString().replaceAll("[{}]", "") + " ciphertext(s); run the re-wrap first");
        }
        if (!kek.configuredVersions().contains(version)) {
            throw new EntityNotFoundException("KEK version '" + version + "' is not known to the KEK provider");
        }
        boolean disabled = kek.disableVersion(version);
        events.publishEvent(new KekVersionRetiredEvent(actorId, actorRole, approverId, version, disabled));
        log.info("KEK version '{}' retired (disabledInProcess={})", version, disabled);
        return new RetireResult(version, disabled);
    }

    /** Periodic snapshot for the gauge; never throws. */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT6H")
    void refreshSnapshot() {
        for (EnvelopeSecretInventory inv : inventories) {
            try {
                remember(inv.type(), inv.countByKekVersion());
            } catch (RuntimeException e) {
                log.warn("KEK inventory scan for {} failed ({})", inv.type(), e.getClass().getSimpleName());
            }
        }
    }

    /** Nightly re-wrap after a rotation (property {@code registerwerk.kek.rewrap.enabled}, default on). */
    @Scheduled(cron = "${registerwerk.kek.rewrap.cron:0 30 3 * * *}")
    @SchedulerLock(name = LOCK_NAME + "Nightly", lockAtMostFor = "PT2H")
    void nightlyRewrap() {
        if (!properties.isEnabled() || kek.activeVersion().isEmpty()) {
            refreshSnapshot();
            return;
        }
        try {
            RewrapReport report = locked(this::runRewrap);
            if (report.rewrapped() + report.failed() > 0) {
                events.publishEvent(completedEvent(null, "SYSTEM", null, report));
                log.info("Nightly KEK re-wrap: {} re-wrapped, {} failed", report.rewrapped(), report.failed());
            }
        } catch (InvalidStateTransitionException e) {
            log.info("Nightly KEK re-wrap skipped: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Nightly KEK re-wrap failed ({})", e.getClass().getSimpleName());
        }
    }

    private RewrapReport runRewrap() {
        Map<String, RewrapOutcome> byType = new LinkedHashMap<>();
        for (EnvelopeSecretInventory inv : inventories) {
            RewrapOutcome outcome;
            try {
                outcome = inv.rewrapStale(properties.getBatchSize());
            } catch (RuntimeException e) {
                log.warn("KEK re-wrap of {} aborted ({})", inv.type(), e.getClass().getSimpleName());
                outcome = new RewrapOutcome(0, 1);
            }
            byType.put(inv.type(), outcome);
            if (outcome.failed() > 0) {
                failures.get(inv.type()).increment(outcome.failed());
            }
        }
        refreshSnapshot();
        return new RewrapReport(byType);
    }

    private <T> T locked(Supplier<T> work) {
        Optional<SimpleLock> lock = lockProvider.lock(
                new LockConfiguration(Instant.now(), LOCK_NAME, Duration.ofHours(2), Duration.ZERO));
        if (lock.isEmpty()) {
            throw new InvalidStateTransitionException("A KEK re-wrap is already running");
        }
        try {
            return work.get();
        } finally {
            lock.get().unlock();
        }
    }

    private void remember(String type, Map<String, Long> countsByVersion) {
        String active = kek.activeVersion().orElse(null);
        long old = active == null ? 0 : countsByVersion.entrySet().stream()
                .filter(e -> !e.getKey().equals(active)).mapToLong(Map.Entry::getValue).sum();
        onOldVersion.put(type, old);
    }

    private KekRewrapCompletedEvent completedEvent(UUID actorId, String role, UUID approverId, RewrapReport report) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        report.byType().forEach((t, o) -> counts.put(t, new int[]{o.rewrapped(), o.failed()}));
        return new KekRewrapCompletedEvent(actorId, role, approverId, kek.activeVersion().orElse(null), counts);
    }
}
