package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Daily re-screen of active beneficial owners (GwG §10 ongoing monitoring — the natural-person
 * half {@code ScreeningService.periodicRefresh} never covered, since {@code screening} cannot
 * depend on {@code kyc} — the module boundary only allows {@code kyc → screening} (see {@code
 * docs/platform/modules.md}). Living here, rather than in {@code screening}, is what lets this
 * job read {@link NaturalPerson} directly instead of needing a reverse dependency.
 *
 * <p>Runs 30 minutes after {@code ScreeningService.periodicRefresh}'s 01:00 entity re-screen so
 * the two jobs don't contend for the same provider rate limits at the same instant.
 */
@Component
class BeneficialOwnerScreeningJob {

    private static final Logger log = LoggerFactory.getLogger(BeneficialOwnerScreeningJob.class);

    private final BeneficialOwnerRepository beneficialOwnerRepository;
    private final NaturalPersonRepository naturalPersonRepository;
    private final ScreeningGate screeningGate;

    BeneficialOwnerScreeningJob(
            BeneficialOwnerRepository beneficialOwnerRepository,
            NaturalPersonRepository naturalPersonRepository,
            ScreeningGate screeningGate) {
        this.beneficialOwnerRepository = beneficialOwnerRepository;
        this.naturalPersonRepository = naturalPersonRepository;
        this.screeningGate = screeningGate;
    }

    @SchedulerLock(name = "beneficialOwnerScreeningRefresh", lockAtMostFor = "PT2H")
    @Scheduled(cron = "0 30 1 * * *")
    @Transactional
    public void run() {
        log.info("Starting periodic beneficial-owner re-screening...");
        List<BeneficialOwner> active = beneficialOwnerRepository.findByCeasedAtIsNull();
        int screened = 0;
        for (BeneficialOwner beneficialOwner : active) {
            NaturalPerson person = naturalPersonRepository.findById(beneficialOwner.getNaturalPersonId()).orElse(null);
            if (person == null) {
                log.warn("Periodic beneficial-owner screening: naturalPerson {} no longer exists, skipping.",
                        beneficialOwner.getNaturalPersonId());
                continue;
            }
            if (person.isRedacted()) {
                // A redacted (erased) person has no PII left to screen against — and shouldn't.
                continue;
            }
            try {
                String fullName = (person.getGivenName() + " " + person.getFamilyName()).trim();
                screeningGate.screenNaturalPerson(person.getId(), fullName, person.getCountry(),
                        ScreeningTrigger.PERIODIC_REFRESH);
                screened++;
            } catch (Exception e) {
                log.error("Periodic screening failed for naturalPerson={}", person.getId(), e);
            }
        }
        log.info("Periodic beneficial-owner screening complete: {} re-screened.", screened);
    }
}
