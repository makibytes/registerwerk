package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Demo only: gives every KYC-APPROVED demo company (and its current beneficial owners) a
 * completed CLEAR screening run per configured provider, so the fail-closed
 * {@code PartyEligibilityGate} / {@code OutboundDestinationGate} do not refuse the seeded
 * counterparties merely because they were "never screened".
 *
 * <p>Runs on every start, independent of the marker-based seeders that return early, so an already
 * seeded database is repaired too. Strictly additive and idempotent: a subject is touched only if it
 * has no run at all for that provider — an entity with any existing run (including the deliberate
 * unresolved HIT demo cases from {@link ScreeningDemoDataSeeder}) is left exactly as it is, and the
 * gate itself is unchanged.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
class ScreeningDemoClearRunRepair implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ScreeningDemoClearRunRepair.class);
    static final String DEMO_ENTITY_PREFIX = "DEMO-";
    private static final List<String> DEMO_LISTS = List.of("DEMO_SEED");

    private final LegalEntityRepository legalEntityRepository;
    private final ScreeningRunRepository runRepository;
    private final List<String> providerNames;

    ScreeningDemoClearRunRepair(LegalEntityRepository legalEntityRepository,
                                ScreeningRunRepository runRepository,
                                List<SanctionsScreeningPort> providers) {
        this.legalEntityRepository = legalEntityRepository;
        this.runRepository = runRepository;
        this.providerNames = providers.stream().map(SanctionsScreeningPort::providerName).distinct().toList();
    }

    @Override
    public int getOrder() {
        return 6; // after DemoDataSeeder (0) and ScreeningDemoDataSeeder (5), before every other demo seeder
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        for (LegalEntity entity : legalEntityRepository.findAll()) {
            if (entity.getEntityNumber() == null || !entity.getEntityNumber().startsWith(DEMO_ENTITY_PREFIX)
                    || entity.getKycStatus() != KycStatus.APPROVED) {
                continue;
            }
            for (String provider : providerNames) {
                if (runRepository.findTopByEntityIdAndProviderOrderByStartedAtDesc(entity.getId(), provider) == null) {
                    runRepository.save(clearRun(entity.getId(), null, provider));
                    created++;
                }
            }
            for (UUID personId : runRepository.findCurrentBeneficialOwnerPersonIds(entity.getId())) {
                for (String provider : providerNames) {
                    if (runRepository.findTopByNaturalPersonIdAndProviderOrderByStartedAtDesc(personId, provider) == null) {
                        runRepository.save(clearRun(null, personId, provider));
                        created++;
                    }
                }
            }
        }
        if (created > 0) {
            log.info("Demo screening: created {} CLEAR run(s) for KYC-approved demo parties without a run.", created);
        }
    }

    private ScreeningRun clearRun(UUID entityId, UUID naturalPersonId, String provider) {
        Instant now = Instant.now();
        ScreeningRun r = new ScreeningRun();
        r.setEntityId(entityId);
        r.setNaturalPersonId(naturalPersonId);
        r.setTriggerType(ScreeningTrigger.ENTITY_ONBOARDING);
        r.setStatus(ScreeningStatus.CLEAR);
        r.setProvider(provider);
        r.setStartedAt(now);
        r.setCompletedAt(now);
        r.setListsChecked(DEMO_LISTS);
        return r;
    }
}
