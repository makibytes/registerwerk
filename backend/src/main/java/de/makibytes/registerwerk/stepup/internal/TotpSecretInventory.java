package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.shared.ColumnSecretInventory;
import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.JdbcSecretColumn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Operator TOTP secrets ({@code app_user.totp_secret}) for the KEK version inventory and re-wrap. */
@Component
public class TotpSecretInventory extends ColumnSecretInventory {

    public TotpSecretInventory(JdbcTemplate jdbc, EnvelopeCipher.KeyWrapper kek) {
        super("TOTP_SECRET", new EnvelopeCipher(kek),
                List.of(new JdbcSecretColumn(jdbc, "app_user", "id", "totp_secret", "totp_secret_kid", kek::name)), 200);
    }
}
