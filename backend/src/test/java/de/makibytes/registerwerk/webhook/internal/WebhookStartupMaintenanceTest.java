package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.api.WebhookSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("WebhookStartupMaintenance — backfill of pre-V28 rows")
class WebhookStartupMaintenanceTest {

    private final WebhookSubscriptionRepository repo = mock(WebhookSubscriptionRepository.class);
    private final WebhookSecretCipher cipher = new WebhookSecretCipher(WebhookSecretCipherTest.XOR_KEK);
    private final OutboundUrlPolicy policy = new OutboundUrlPolicy(new WebhookProperties(), false, host -> {
        try {
            return InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    });
    private final WebhookStartupMaintenance maintenance = new WebhookStartupMaintenance(
            repo, cipher, policy, mock(ApplicationEventPublisher.class), new NoopTransactionManager());

    private WebhookSubscription legacy(String url, String plainSecret) {
        WebhookSubscription s = new WebhookSubscription();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setEntityId(UUID.randomUUID());
        s.setUrl(url);
        s.setSecret(plainSecret);
        when(repo.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    @DisplayName("plaintext secrets are encrypted (and still verify), invalid URLs are disabled with URL_POLICY, nothing is deleted; second run is a no-op")
    void backfill() {
        WebhookSubscription good = legacy("https://93.184.216.34/hook", "plain-1");
        WebhookSubscription bad = legacy("http://169.254.169.254/latest", "plain-2");
        when(repo.findAll()).thenReturn(List.of(good, bad));

        int[] first = maintenance.runOnce();

        assertThat(first).containsExactly(2, 1);
        assertThat(good.getSecret()).startsWith("enc:v1:");
        assertThat(cipher.decrypt(good.getSecret(), good.getId())).isEqualTo("plain-1");
        assertThat(good.isEnabled()).isTrue();
        assertThat(bad.getSecret()).startsWith("enc:v1:");
        assertThat(bad.isEnabled()).isFalse();
        assertThat(bad.getDisabledReason()).isEqualTo("URL_POLICY");

        int[] second = maintenance.runOnce();
        assertThat(second).containsExactly(0, 0);
    }
}
