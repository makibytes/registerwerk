package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.kyc.events.NaturalPersonPepStatusChangedEvent;
import de.makibytes.registerwerk.screening.events.ScreeningPepConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Writes {@code NaturalPerson.pepStatus = CONFIRMED_PEP} when screening confirms a PEP hit (6-17; the field
 * had no writer). Runs synchronously in the confirming transaction so the status and the hit resolution
 * commit together. Entities where the person is a current beneficial owner and whose KYC is APPROVED get a
 * KYC_REVIEW_REQUIRED task: the PEP now needs an EDD approval, the status is not changed automatically.
 */
@Component
class PepConfirmationListener {

    private static final Logger log = LoggerFactory.getLogger(PepConfirmationListener.class);

    private final NaturalPersonRepository naturalPersonRepository;
    private final BeneficialOwnerRepository beneficialOwnerRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final EntityTaskPort entityTaskPort;
    private final ApplicationEventPublisher eventPublisher;

    PepConfirmationListener(NaturalPersonRepository naturalPersonRepository,
                            BeneficialOwnerRepository beneficialOwnerRepository,
                            LegalEntityRepository legalEntityRepository,
                            EntityTaskPort entityTaskPort,
                            ApplicationEventPublisher eventPublisher) {
        this.naturalPersonRepository = naturalPersonRepository;
        this.beneficialOwnerRepository = beneficialOwnerRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.entityTaskPort = entityTaskPort;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    @Transactional
    void onPepConfirmed(ScreeningPepConfirmedEvent event) {
        NaturalPerson person = naturalPersonRepository.findById(event.naturalPersonId()).orElse(null);
        if (person == null) {
            log.warn("PEP confirmed for unknown natural person {}", event.naturalPersonId());
            return;
        }
        NaturalPerson.PepStatus previous = person.getPepStatus();
        if (previous != NaturalPerson.PepStatus.CONFIRMED_PEP) {
            person.setPepStatus(NaturalPerson.PepStatus.CONFIRMED_PEP);
            naturalPersonRepository.save(person);
            eventPublisher.publishEvent(new NaturalPersonPepStatusChangedEvent(person.getId(), event.actorId(),
                    event.actorRole(), Map.of(
                            "from", previous.name(),
                            "to", NaturalPerson.PepStatus.CONFIRMED_PEP.name(),
                            "screeningHitId", event.hitId().toString())));
        }
        for (BeneficialOwner bo : beneficialOwnerRepository.findByNaturalPersonId(person.getId())) {
            if (bo.getCeasedAt() != null) {
                continue;
            }
            legalEntityRepository.findById(bo.getEntityId())
                    .filter(entity -> entity.getKycStatus() == KycStatus.APPROVED)
                    .ifPresent(entity -> entityTaskPort.open(entity.getId(), EntityTask.KYC_REVIEW_REQUIRED,
                            "PEP:" + person.getId(),
                            "A beneficial owner was confirmed as a PEP; an EDD approval is required. "
                                    + "The KYC status was not changed automatically.", event.actorId()));
        }
    }
}
