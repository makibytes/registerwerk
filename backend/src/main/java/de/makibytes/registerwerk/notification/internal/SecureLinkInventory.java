package de.makibytes.registerwerk.notification.internal;

import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.EnvelopeSecretInventory;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secure-link seals exist only inside event publications that have not completed yet (invite / reset links on
 * their way to the mailer). They are counted so that a KEK version is not retired under an undelivered link, but
 * never rewritten: Spring Modulith completes a publication by matching its serialized form, so editing it would
 * strand the publication. They drain when the publication completes or the link expires.
 */
@Component
public class SecureLinkInventory implements EnvelopeSecretInventory {

    private static final Pattern SEAL = Pattern.compile("enc:v1:[A-Za-z0-9+/=]+");

    private final JdbcTemplate jdbc;
    private final EnvelopeCipher cipher;

    public SecureLinkInventory(JdbcTemplate jdbc, KekProvider kek) {
        this.jdbc = jdbc;
        this.cipher = new EnvelopeCipher(kek);
    }

    @Override
    public String type() {
        return "SECURE_LINK_IN_FLIGHT";
    }

    @Override
    public Map<String, Long> countByKekVersion() {
        Map<String, Long> counts = new TreeMap<>();
        for (String serialized : jdbc.queryForList(
                "SELECT serialized_event FROM event_publication WHERE completion_date IS NULL"
                        + " AND serialized_event LIKE '%enc:v1:%'", String.class)) {
            Matcher m = SEAL.matcher(serialized);
            while (m.find()) {
                counts.merge(cipher.versionLabel(m.group()), 1L, Long::sum);
            }
        }
        return counts;
    }

    @Override
    public RewrapOutcome rewrapStale(int pageSize) {
        return new RewrapOutcome(0, 0);
    }
}
