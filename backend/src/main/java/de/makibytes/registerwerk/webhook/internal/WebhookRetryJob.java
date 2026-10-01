package de.makibytes.registerwerk.webhook.internal;

import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Retry sweep for failed webhook deliveries, run every minute. It claims due rows in one short
 * transaction ({@code FOR UPDATE SKIP LOCKED} plus a lease), then sends them <em>outside</em> any
 * transaction on a bounded pool ({@code registerwerk.webhook.max-concurrency}) with a per-host cap
 * ({@code max-per-host}). A hanging endpoint can therefore only ever occupy its own slot(s) for the
 * connect/response timeout; other tenants' deliveries proceed in parallel (5D-08).
 */
@Component
class WebhookRetryJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryJob.class);

    private final WebhookDispatchService dispatchService;
    private final WebhookProperties properties;
    private final ExecutorService pool;
    private final Map<String, Semaphore> hostPermits = new ConcurrentHashMap<>();

    WebhookRetryJob(WebhookDispatchService dispatchService, WebhookProperties properties) {
        this.dispatchService = dispatchService;
        this.properties = properties;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(1, properties.getMaxConcurrency()), r -> {
            Thread t = new Thread(r, "webhook-sweep-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "webhookRetry", lockAtMostFor = "PT4M")
    public void retryFailedDeliveries() {
        int processed = sweep();
        if (processed > 0) {
            log.info("Webhook sweep processed {} due deliveries.", processed);
        }
    }

    /** One sweep; returns the number of deliveries attempted. Package-private for tests. */
    int sweep() {
        List<WebhookDispatchService.DueDelivery> due = dispatchService.claimDue();
        if (due.isEmpty()) return 0;
        AtomicInteger attempted = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>(due.size());
        for (WebhookDispatchService.DueDelivery d : due) {
            tasks.add(() -> {
                Semaphore permit = hostPermits.computeIfAbsent(d.host(),
                        h -> new Semaphore(Math.max(1, properties.getMaxPerHost())));
                if (!permit.tryAcquire()) {
                    dispatchService.defer(d.deliveryId(), Duration.ofSeconds(10));
                    return null;
                }
                try {
                    dispatchService.attempt(d.deliveryId());
                    attempted.incrementAndGet();
                } catch (RuntimeException e) {
                    log.warn("Webhook retry failed for deliveryId={}: {}", d.deliveryId(), e.getClass().getSimpleName());
                } finally {
                    permit.release();
                }
                return null;
            });
        }
        try {
            pool.invokeAll(tasks, 3, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return attempted.get();
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
