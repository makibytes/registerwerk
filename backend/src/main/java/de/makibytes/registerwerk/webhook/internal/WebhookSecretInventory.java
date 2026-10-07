package de.makibytes.registerwerk.webhook.internal;

import de.makibytes.registerwerk.shared.ColumnSecretInventory;
import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.JdbcSecretColumn;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Webhook signing secrets, current and previous (rotation window), for the KEK version inventory and re-wrap. */
@Component
public class WebhookSecretInventory extends ColumnSecretInventory {

    public WebhookSecretInventory(JdbcTemplate jdbc, KekProvider kek) {
        super("WEBHOOK_SECRET", new EnvelopeCipher(kek), List.of(
                new JdbcSecretColumn(jdbc, "webhook_subscription", "id", "secret"),
                new JdbcSecretColumn(jdbc, "webhook_subscription", "id", "secret_previous_enc")), 200);
    }
}
