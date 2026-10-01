package de.makibytes.registerwerk.webhook.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WebhookRetryJob — a slow endpoint must not stall other tenants (5D-08)")
class WebhookRetryJobTest {

    @Test
    @DisplayName("deliveries to a hanging host do not delay deliveries to other hosts in the same sweep")
    void slowEndpointDoesNotBlockOthers() throws Exception {
        WebhookDispatchService dispatch = mock(WebhookDispatchService.class);
        WebhookProperties props = new WebhookProperties();
        props.setMaxConcurrency(4);
        props.setMaxPerHost(1);

        UUID slow = UUID.randomUUID();
        UUID fastA = UUID.randomUUID();
        UUID fastB = UUID.randomUUID();
        when(dispatch.claimDue()).thenReturn(List.of(
                new WebhookDispatchService.DueDelivery(slow, "slow.example.com"),
                new WebhookDispatchService.DueDelivery(fastA, "a.example.com"),
                new WebhookDispatchService.DueDelivery(fastB, "b.example.com")));

        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(2);
        doAnswer(inv -> {
            if (slow.equals(inv.getArgument(0))) {
                releaseSlow.await(10, TimeUnit.SECONDS);
            } else {
                fastDone.countDown();
            }
            return null;
        }).when(dispatch).attempt(any(UUID.class));

        WebhookRetryJob job = new WebhookRetryJob(dispatch, props);
        Thread sweeper = new Thread(job::sweep);
        sweeper.start();

        // the two fast tenants complete while the slow one is still blocked
        assertThat(fastDone.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(sweeper.isAlive()).isTrue();

        releaseSlow.countDown();
        sweeper.join(5000);
        assertThat(sweeper.isAlive()).isFalse();
        job.shutdown();
    }

    @Test
    @DisplayName("per-host cap: surplus deliveries for the same host are deferred, not blocking a pool thread")
    void perHostCapDefersSurplus() throws Exception {
        WebhookDispatchService dispatch = mock(WebhookDispatchService.class);
        WebhookProperties props = new WebhookProperties();
        props.setMaxConcurrency(4);
        props.setMaxPerHost(1);

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(dispatch.claimDue()).thenReturn(List.of(
                new WebhookDispatchService.DueDelivery(first, "same.example.com"),
                new WebhookDispatchService.DueDelivery(second, "same.example.com")));
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(inv -> {
            attempts.incrementAndGet();
            hold.await(2, TimeUnit.SECONDS);
            return null;
        }).when(dispatch).attempt(any(UUID.class));

        WebhookRetryJob job = new WebhookRetryJob(dispatch, props);
        Thread sweeper = new Thread(job::sweep);
        sweeper.start();
        Thread.sleep(500);
        hold.countDown();
        sweeper.join(5000);

        assertThat(attempts.get()).isEqualTo(1);
        verify(dispatch).defer(any(UUID.class), any(Duration.class));
        job.shutdown();
    }

    @Test
    @DisplayName("an empty claim does nothing")
    void emptySweep() {
        WebhookDispatchService dispatch = mock(WebhookDispatchService.class);
        when(dispatch.claimDue()).thenReturn(List.of());
        WebhookRetryJob job = new WebhookRetryJob(dispatch, new WebhookProperties());
        assertThat(job.sweep()).isZero();
        job.shutdown();
    }
}
