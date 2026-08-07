package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.webhook.api.WebhookDelivery;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryRepository;
import de.makibytes.registerwerk.webhook.api.WebhookDeliveryStatus;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Retries FAILED webhook deliveries every 5 minutes, up to {@link WebhookDispatchService#MAX_ATTEMPTS}
 * — a transient outage at the receiver's endpoint must not silently drop an event forever.
 */
@Component
class WebhookRetryJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryJob.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDispatchService dispatchService;

    WebhookRetryJob(WebhookDeliveryRepository deliveryRepository,
                     WebhookSubscriptionRepository subscriptionRepository,
                     WebhookDispatchService dispatchService) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.dispatchService = dispatchService;
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "webhookRetry", lockAtMostFor = "PT4M")
    @Transactional
    public void retryFailedDeliveries() {
        List<WebhookDelivery> failed = deliveryRepository.findByStatusOrderByCreatedAtAsc(WebhookDeliveryStatus.FAILED);
        int retried = 0;
        for (WebhookDelivery delivery : failed) {
            if (delivery.getAttemptCount() >= WebhookDispatchService.MAX_ATTEMPTS) {
                continue;
            }
            WebhookSubscription subscription = subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
            if (subscription == null || !subscription.isEnabled()) {
                continue;
            }
            dispatchService.attempt(delivery, subscription);
            retried++;
        }
        if (retried > 0) {
            log.info("Retried {} failed webhook deliveries.", retried);
        }
    }
}
