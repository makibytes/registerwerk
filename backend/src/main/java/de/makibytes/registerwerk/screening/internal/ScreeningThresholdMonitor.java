package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.events.ScreeningThresholdChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Logs the effective match threshold at startup and audits a change against the last recorded run (6-19). */
@Component
class ScreeningThresholdMonitor {

    private static final Logger log = LoggerFactory.getLogger(ScreeningThresholdMonitor.class);

    private final ScreeningRunRepository runRepository;
    private final ApplicationEventPublisher events;
    private final BigDecimal threshold;

    ScreeningThresholdMonitor(ScreeningRunRepository runRepository, ApplicationEventPublisher events,
                              @Value("${registerwerk.screening.match-threshold:0.85}") BigDecimal threshold) {
        this.runRepository = runRepository;
        this.events = events;
        this.threshold = threshold;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onReady() {
        log.info("Screening match threshold = {}", threshold);
        runRepository.findFirstByThresholdUsedIsNotNullOrderByStartedAtDesc().ifPresent(last -> {
            if (last.getThresholdUsed().compareTo(threshold) != 0) {
                log.warn("Screening match threshold changed: {} -> {}", last.getThresholdUsed(), threshold);
                events.publishEvent(new ScreeningThresholdChangedEvent(last.getThresholdUsed(), threshold));
            }
        });
    }
}
