package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.KycStatus;
import de.makibytes.registerwerk.kyc.events.KycExpiringEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/**
 * GwG §10 Abs. 1 Nr. 5 ongoing-monitoring scheduler.
 * Flips KYC status APPROVED → EXPIRED 30 days before kyc_expiry_date
 * and emits events that trigger notification + identity-registry removal.
 */
@Component
class KycMonitoringJob {

    private static final Logger log = LoggerFactory.getLogger(KycMonitoringJob.class);

    private final LegalEntityRepository entityRepository;
    private final ApplicationEventPublisher events;

    KycMonitoringJob(LegalEntityRepository entityRepository, ApplicationEventPublisher events) {
        this.entityRepository = entityRepository;
        this.events = events;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void run() {
        LocalDate today = LocalDate.now();
        LocalDate warningThreshold = today.plusDays(30);
        LocalDate expiredThreshold = today;

        // Flip APPROVED → EXPIRED for entities whose kyc_expiry_date has passed
        var expired = entityRepository.findAll().stream()
                .filter(e -> KycStatus.APPROVED.equals(e.getKycStatus())
                        && e.getKycExpiryDate() != null
                        && !e.getKycExpiryDate().isAfter(expiredThreshold))
                .toList();

        for (var entity : expired) {
            entity.setKycStatus(KycStatus.EXPIRED);
            entityRepository.save(entity);
            events.publishEvent(new KycExpiringEvent(entity.getId(), null,
                    Map.of("reason", "EXPIRED", "expiryDate", entity.getKycExpiryDate().toString())));
            log.warn("KYC expired for entity={} expiry={}", entity.getId(), entity.getKycExpiryDate());
        }

        // Warn for entities expiring within 30 days
        var expiringSoon = entityRepository.findAll().stream()
                .filter(e -> KycStatus.APPROVED.equals(e.getKycStatus())
                        && e.getKycExpiryDate() != null
                        && e.getKycExpiryDate().isAfter(expiredThreshold)
                        && !e.getKycExpiryDate().isAfter(warningThreshold))
                .toList();

        for (var entity : expiringSoon) {
            events.publishEvent(new KycExpiringEvent(entity.getId(), null,
                    Map.of("reason", "EXPIRING_SOON", "expiryDate", entity.getKycExpiryDate().toString(),
                           "daysRemaining", String.valueOf(today.until(entity.getKycExpiryDate()).getDays()))));
            log.info("KYC expiring soon for entity={} expiry={}", entity.getId(), entity.getKycExpiryDate());
        }

        if (!expired.isEmpty() || !expiringSoon.isEmpty()) {
            log.info("KYC monitoring: {} expired, {} expiring within 30 days.", expired.size(), expiringSoon.size());
        }
    }
}
