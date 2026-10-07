package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.travelrule.events.TravelRuleControlEvent;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-peer credentials of the inbound Travel Rule inbox (6-29). Each registered peer VASP owns a random
 * HMAC key (envelope-encrypted at rest, AAD bound to the peer id). A request is authentic only if its
 * {@code X-Registerwerk-Peer-Signature} equals HMAC-SHA256 over {@code timestamp|vaspId|sha256(body)}, the
 * timestamp is inside the freshness window, and the signature has not been seen before (replay cache).
 * The authenticated peer id - never a free header - is what the inbox dedups and attributes on.
 */
@Service
public class TravelRulePeerService {

    private final JdbcTemplate jdbc;
    private final EnvelopeCipher cipher;
    private final String kid;
    private final ApplicationEventPublisher events;
    private final TravelRuleProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    TravelRulePeerService(JdbcTemplate jdbc, KekProvider kek, ApplicationEventPublisher events,
                          TravelRuleProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = new EnvelopeCipher(kek);
        this.kid = kek.name();
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public record PeerView(String vaspId, String legalName, String lei, String status, Instant createdAt) {}

    /** The key is returned exactly once; it is never readable again. */
    public record CreatedPeer(PeerView peer, String hmacKey) {}

    @Transactional
    public CreatedPeer register(String vaspId, String legalName, String lei, UUID actorId, String actorRole,
                                UUID approverId) {
        if (approverId == null) {
            throw new AccessDeniedException("Registering a Travel Rule peer requires a second approver");
        }
        String id = vaspId.trim();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // Re-registering rotates the key but NEVER reactivates a DISABLED peer: that is the explicit enable() action
        // (own dual-control + audit). "inserted" is xmax = 0 on the returned row, so "rotated" is truthful.
        Boolean inserted = jdbc.queryForObject("""
            INSERT INTO travel_rule_peer (vasp_id, legal_name, lei, hmac_key_ciphertext, key_kid, status,
                                          created_by, second_approver_id)
            VALUES (?,?,?,?,?,'ACTIVE',?,?)
            ON CONFLICT (vasp_id) DO UPDATE SET hmac_key_ciphertext = EXCLUDED.hmac_key_ciphertext,
                key_kid = EXCLUDED.key_kid, legal_name = EXCLUDED.legal_name, lei = EXCLUDED.lei,
                created_by = EXCLUDED.created_by, second_approver_id = EXCLUDED.second_approver_id, updated_at = now()
            RETURNING (xmax = 0)
            """, Boolean.class, id, legalName, lei, cipher.encrypt(key, aad(id)), kid, actorId, approverId);
        boolean rotated = !Boolean.TRUE.equals(inserted);
        PeerView peer = view(id);
        events.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.PEER_CREATED, "TravelRulePeer",
                UUID.nameUUIDFromBytes(id.toLowerCase().getBytes(StandardCharsets.UTF_8)), actorId, actorRole,
                approverId, Map.of("vaspId", id, "rotated", rotated, "status", peer.status())));
        return new CreatedPeer(peer, key);
    }

    @Transactional
    public void disable(String vaspId, UUID actorId, String actorRole, UUID approverId) {
        if (approverId == null) {
            throw new AccessDeniedException("Disabling a Travel Rule peer requires a second approver");
        }
        int n = jdbc.update("UPDATE travel_rule_peer SET status='DISABLED', updated_at=now() WHERE vasp_id=?", vaspId);
        if (n == 0) {
            throw new EntityNotFoundException("TravelRulePeer", UUID.nameUUIDFromBytes(vaspId.toLowerCase().getBytes(StandardCharsets.UTF_8)));
        }
        events.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.PEER_DISABLED, "TravelRulePeer",
                UUID.nameUUIDFromBytes(vaspId.toLowerCase().getBytes(StandardCharsets.UTF_8)), actorId, actorRole,
                approverId, Map.of("vaspId", vaspId)));
    }

    /** The only way back from DISABLED (a plain re-registration rotates the key but keeps the status). */
    @Transactional
    public void enable(String vaspId, UUID actorId, String actorRole, UUID approverId) {
        if (approverId == null) {
            throw new AccessDeniedException("Enabling a Travel Rule peer requires a second approver");
        }
        int n = jdbc.update("UPDATE travel_rule_peer SET status='ACTIVE', updated_at=now() WHERE vasp_id=?", vaspId);
        if (n == 0) {
            throw new EntityNotFoundException("TravelRulePeer", UUID.nameUUIDFromBytes(vaspId.toLowerCase().getBytes(StandardCharsets.UTF_8)));
        }
        events.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.PEER_ENABLED, "TravelRulePeer",
                UUID.nameUUIDFromBytes(vaspId.toLowerCase().getBytes(StandardCharsets.UTF_8)), actorId, actorRole,
                approverId, Map.of("vaspId", vaspId)));
    }

    @Transactional(readOnly = true)
    public List<PeerView> list() {
        return jdbc.query("SELECT vasp_id, legal_name, lei, status, created_at FROM travel_rule_peer ORDER BY vasp_id",
                (rs, i) -> new PeerView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()));
    }

    private PeerView view(String vaspId) {
        return jdbc.query("SELECT vasp_id, legal_name, lei, status, created_at FROM travel_rule_peer WHERE vasp_id=?",
                (rs, i) -> new PeerView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()), vaspId).get(0);
    }

    /**
     * Authenticates an inbox request. Returns the peer id as stored in the register (the only identity the
     * caller may act as) or throws {@link AccessDeniedException}; the reason is deliberately generic.
     */
    @Transactional
    public String authenticate(String claimedVaspId, String timestamp, String signature, byte[] body) {
        if (claimedVaspId == null || claimedVaspId.isBlank() || timestamp == null || signature == null) {
            throw denied();
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw denied();
        }
        long now = Instant.now(clock).getEpochSecond();
        if (Math.abs(now - ts) > properties.getInboundWindowSeconds()) {
            throw denied();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT vasp_id, hmac_key_ciphertext FROM travel_rule_peer
             WHERE LOWER(vasp_id) = LOWER(?) AND status = 'ACTIVE' AND hmac_key_ciphertext IS NOT NULL
            """, claimedVaspId.trim());
        if (rows.isEmpty()) {
            throw denied();
        }
        String peerId = (String) rows.get(0).get("vasp_id");
        String key = cipher.decrypt((String) rows.get(0).get("hmac_key_ciphertext"), aad(peerId));
        String expected = sign(key, timestamp.trim(), peerId, body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw denied();
        }
        int fresh = jdbc.update("INSERT INTO travel_rule_replay (vasp_id, signature_hash) VALUES (?,?) ON CONFLICT DO NOTHING",
                peerId, TravelRuleService.sha256Hex(expected));
        if (fresh == 0) {
            throw denied();
        }
        return peerId;
    }

    /** Signature a peer computes: hex(HMAC-SHA256(key, timestamp|vaspId|sha256hex(body))). */
    public static String sign(String key, String timestamp, String vaspId, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((timestamp + "|" + vaspId + "|" + bodyHash).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public int purgeReplayCache() {
        return jdbc.update("DELETE FROM travel_rule_replay WHERE seen_at < now() - make_interval(secs => ?)",
                properties.getInboundWindowSeconds() * 3);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Invalid Travel Rule peer credential");
    }

    private static String aad(String vaspId) {
        return "travel-rule-peer:" + vaspId;
    }
}
