package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Seeds a small screening history so the demo instance shows both hit categories
 * ({@link HitCategory#SANCTIONS} and {@link HitCategory#PEP}) side by side in the
 * compliance screening queue. Runs after {@code bootstrap.DemoDataSeeder} (which
 * creates the legal entities this seeder attaches to) but lives in this module —
 * not {@code bootstrap} — because {@link ScreeningRunRepository}/{@link ScreeningHitRepository}
 * are package-private to {@code screening.internal} by design.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
class ScreeningDemoDataSeeder implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ScreeningDemoDataSeeder.class);

    private final LegalEntityRepository legalEntityRepository;
    private final ScreeningRunRepository runRepository;
    private final ScreeningHitRepository hitRepository;

    ScreeningDemoDataSeeder(LegalEntityRepository legalEntityRepository,
                            ScreeningRunRepository runRepository,
                            ScreeningHitRepository hitRepository) {
        this.legalEntityRepository = legalEntityRepository;
        this.runRepository = runRepository;
        this.hitRepository = hitRepository;
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LegalEntity meridian = legalEntityRepository.findByEntityNumber("DEMO-MC-001").orElse(null);
        LegalEntity aurora = legalEntityRepository.findByEntityNumber("DEMO-AF-001").orElse(null);
        if (meridian == null || aurora == null) {
            return; // bootstrap.DemoDataSeeder disabled or not run yet
        }
        if (!runRepository.findByEntityIdOrderByStartedAtDesc(meridian.getId()).isEmpty()) {
            return; // already seeded
        }

        log.info("Seeding demo screening history…");

        // A resolved sanctions false-positive — common name collision, accepted after review.
        run(meridian.getId(), ScreeningStatus.CLEAR, Instant.now().minus(60, ChronoUnit.DAYS), null);
        ScreeningRun sanctionsRun = run(meridian.getId(), ScreeningStatus.HIT,
                Instant.now().minus(45, ChronoUnit.DAYS), null);
        hit(sanctionsRun.getId(), "OPEN_SANCTIONS", HitCategory.SANCTIONS,
                "name", "Meridian Capital Ltd (name-collision, unrelated UK entity)", "0.88");

        // An open PEP hit on a beneficial owner — needs enhanced due diligence, not a block.
        ScreeningRun pepRun = run(aurora.getId(), ScreeningStatus.HIT,
                Instant.now().minus(5, ChronoUnit.DAYS), null);
        hit(pepRun.getId(), "OPEN_SANCTIONS", HitCategory.PEP,
                "name", "Politically exposed person — former regional minister (family member of a UBO)", "0.91");

        log.info("Demo screening history seeded: 3 runs, 2 open hits (1 SANCTIONS, 1 PEP).");
    }

    private ScreeningRun run(java.util.UUID entityId, ScreeningStatus status, Instant startedAt, String error) {
        ScreeningRun r = new ScreeningRun();
        r.setEntityId(entityId);
        r.setTriggerType(ScreeningTrigger.ENTITY_ONBOARDING);
        r.setStatus(status);
        r.setProvider("OPEN_SANCTIONS");
        r.setStartedAt(startedAt);
        r.setCompletedAt(startedAt.plusSeconds(2));
        r.setErrorMessage(error);
        return runRepository.save(r);
    }

    private void hit(java.util.UUID runId, String listSource, HitCategory category,
                     String matchedField, String matchedValue, String matchScore) {
        ScreeningHit h = new ScreeningHit();
        h.setRunId(runId);
        h.setListSource(listSource);
        h.setCategory(category);
        h.setMatchedField(matchedField);
        h.setMatchedValue(matchedValue);
        h.setMatchScore(new BigDecimal(matchScore));
        hitRepository.save(h);
    }
}
