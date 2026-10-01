package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import de.makibytes.registerwerk.webhook.events.WebhookSubscriptionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * One-time backfill for subscriptions that pre-date V28 / the outbound URL policy (idempotent, safe to
 * run on every start and on several nodes at once):
 * <ol>
 *   <li>encrypts secrets still stored as plaintext ({@code enc:} prefix missing) — until then
 *       {@link WebhookSecretCipher#decrypt} reads them as legacy plaintext;</li>
 *   <li>re-validates every enabled URL against {@link OutboundUrlPolicy} and disables violators with
 *       {@code disabled_reason=URL_POLICY} (never deletes). A URL that merely fails to resolve right now
 *       is left alone.</li>
 * </ol>
 * Each subscription is handled in its own transaction and failures are logged, never fatal to boot.
 */
@Component
class WebhookStartupMaintenance implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WebhookStartupMaintenance.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookSecretCipher secretCipher;
    private final OutboundUrlPolicy urlPolicy;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate tx;

    WebhookStartupMaintenance(WebhookSubscriptionRepository subscriptionRepository, WebhookSecretCipher secretCipher,
                              OutboundUrlPolicy urlPolicy, ApplicationEventPublisher eventPublisher,
                              PlatformTransactionManager txManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.secretCipher = secretCipher;
        this.urlPolicy = urlPolicy;
        this.eventPublisher = eventPublisher;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int[] counts = runOnce();
            if (counts[0] > 0 || counts[1] > 0) {
                log.info("Webhook maintenance: encrypted {} legacy secret(s), disabled {} subscription(s) for URL policy.",
                        counts[0], counts[1]);
            }
        } catch (RuntimeException e) {
            log.error("Webhook startup maintenance failed; will retry on next start", e);
        }
    }

    /** @return {encryptedSecrets, urlPolicyDisabled} */
    int[] runOnce() {
        int encrypted = 0;
        int disabled = 0;
        for (WebhookSubscription snapshot : subscriptionRepository.findAll()) {
            try {
                Integer[] r = tx.execute(status -> maintain(snapshot.getId()));
                if (r != null) {
                    encrypted += r[0];
                    disabled += r[1];
                }
            } catch (RuntimeException e) {
                log.warn("Webhook maintenance failed for subscription {}: {}", snapshot.getId(), e.getClass().getSimpleName());
            }
        }
        return new int[]{encrypted, disabled};
    }

    private Integer[] maintain(java.util.UUID id) {
        WebhookSubscription s = subscriptionRepository.findById(id).orElse(null);
        if (s == null) return null;
        int encrypted = 0;
        int disabled = 0;
        if (!WebhookSecretCipher.isEncrypted(s.getSecret())) {
            s.setSecret(secretCipher.encrypt(s.getSecret(), s.getId()));
            encrypted = 1;
        }
        if (s.getSecretPreviousEnc() != null && !WebhookSecretCipher.isEncrypted(s.getSecretPreviousEnc())) {
            s.setSecretPreviousEnc(secretCipher.encrypt(s.getSecretPreviousEnc(), s.getId()));
        }
        if (s.isEnabled() && urlPolicy.check(s.getUrl()) == OutboundUrlPolicy.Verdict.BLOCKED) {
            s.setEnabled(false);
            s.setDisabledReason("URL_POLICY");
            disabled = 1;
            eventPublisher.publishEvent(new WebhookSubscriptionChangedEvent(s.getId(), s.getEntityId(), null,
                    "SYSTEM", "AUTO_DISABLED", Map.of("reason", "URL_POLICY")));
        }
        if (encrypted + disabled > 0) {
            subscriptionRepository.save(s);
        }
        return new Integer[]{encrypted, disabled};
    }
}
