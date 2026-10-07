package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.shared.ColumnSecretInventory;
import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.JdbcSecretColumn;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Travel Rule peer HMAC keys ({@code travel_rule_peer.hmac_key_ciphertext}) for the KEK version inventory and re-wrap. */
@Component
public class TravelRulePeerKeyInventory extends ColumnSecretInventory {

    public TravelRulePeerKeyInventory(JdbcTemplate jdbc, KekProvider kek) {
        super("TRAVEL_RULE_PEER_KEY", new EnvelopeCipher(kek), List.of(
                new JdbcSecretColumn(jdbc, "travel_rule_peer", "vasp_id", "hmac_key_ciphertext", "key_kid", kek::name)), 200);
    }
}
