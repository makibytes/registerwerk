package de.makibytes.registerwerk.stepup.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-off startup migration of TOTP secrets stored as plaintext before V35 (6-09). Idempotent and safe on
 * several nodes at once (compare-and-set on the old value); the plaintext is overwritten by the
 * ciphertext. Failures are logged, never fatal: the next verification re-encrypts lazily as well.
 */
@Component
class TotpSecretMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TotpSecretMigration.class);

    private final JdbcTemplate jdbc;
    private final TotpSecretStore store;

    TotpSecretMigration(JdbcTemplate jdbc, TotpSecretStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = migrate();
            if (n > 0) {
                log.info("Encrypted {} legacy plaintext TOTP secret(s) at rest.", n);
            }
        } catch (RuntimeException e) {
            log.error("TOTP secret migration failed; secrets are re-encrypted lazily on next use", e);
        }
    }

    int migrate() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, totp_secret FROM app_user WHERE totp_secret IS NOT NULL AND totp_secret NOT LIKE 'enc:%'");
        int done = 0;
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            String plain = (String) row.get("totp_secret");
            try {
                done += jdbc.update("UPDATE app_user SET totp_secret = ?, totp_secret_kid = ? WHERE id = ? AND totp_secret = ?",
                        store.encrypt(id, plain), store.kid(), id, plain);
            } catch (RuntimeException e) {
                log.warn("Could not encrypt TOTP secret of user {}: {}", id, e.getClass().getSimpleName());
            }
        }
        return done;
    }
}
