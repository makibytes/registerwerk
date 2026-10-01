package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.WalletSignaturePort;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.travelrule.events.TravelRuleControlEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Art. 14(5) TFR: evidence that a registered holder wallet is owned/controlled by the holder (the
 * customer of this register). Two ways in, both reuse existing mechanisms rather than inventing a scheme:
 * <ol>
 *   <li>{@code SIGNED_MESSAGE} - an EIP-191 {@code personal_sign} nonce challenge, verified through the
 *       {@link WalletSignaturePort} (implemented by orgidentity on its shared verifier) (EOA ECDSA recovery or ERC-1271 for contract wallets);</li>
 *   <li>{@code OPERATOR_ATTESTATION} - for holder wallets that cannot sign (custodial, legacy): an
 *       operator attestation with a mandatory evidence note, created with step-up and a second approver.</li>
 * </ol>
 * A proof only counts for the (legal entity, wallet) pair it was made for and only while that entity is
 * the registered holder of the wallet - a proof for another entity's wallet is never accepted.
 */
@Service
public class WalletControlProofService {

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final WalletSignaturePort verifier;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int validityDays;
    private final SecureRandom random = new SecureRandom();

    WalletControlProofService(JdbcTemplate jdbc, WalletSignaturePort verifier, ApplicationEventPublisher events,
                              Clock clock,
                              @Value("${registerwerk.travel-rule.wallet-proof-validity-days:365}") int validityDays) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.events = events;
        this.clock = clock;
        this.validityDays = validityDays;
    }

    public record Challenge(UUID proofId, String message, Instant expiresAt) {}

    public record ProofView(UUID id, UUID legalEntityId, String walletAddress, String method, String status,
                            Instant verifiedAt, Instant expiresAt, String evidenceRef) {}

    /** Valid (VERIFIED, unexpired) proof for the registered holder of {@code wallet} on {@code assetId}. */
    @Transactional(readOnly = true)
    public Optional<UUID> findValidProof(UUID assetId, String wallet) {
        if (assetId == null || wallet == null || wallet.isBlank()) {
            return Optional.empty();
        }
        List<UUID> ids = jdbc.queryForList("""
            SELECT p.id FROM wallet_control_proof p
              JOIN asset_holder ah ON ah.investor_id = p.legal_entity_id
                                  AND LOWER(ah.wallet_address) = LOWER(p.wallet_address)
             WHERE ah.asset_id = ? AND LOWER(p.wallet_address) = LOWER(?)
               AND p.status = 'VERIFIED' AND (p.expires_at IS NULL OR p.expires_at > now())
             ORDER BY p.verified_at DESC LIMIT 1
            """, UUID.class, assetId, wallet);
        return ids.stream().findFirst();
    }

    /** Is {@code wallet} a registered holder wallet of the register (any holder) for this asset? */
    @Transactional(readOnly = true)
    public boolean isRegisteredHolderWallet(UUID assetId, String wallet) {
        if (assetId == null || wallet == null) {
            return false;
        }
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM asset_holder WHERE asset_id = ? AND LOWER(wallet_address) = LOWER(?)
            """, Integer.class, assetId, wallet);
        return n != null && n > 0;
    }

    @Transactional
    public Challenge createChallenge(UUID legalEntityId, String wallet, UUID chainConfigId) {
        requireHolderWallet(legalEntityId, wallet);
        if (chainConfigId == null) {
            throw new IllegalArgumentException("chainConfigId is required for a signed-message proof");
        }
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        Instant expires = Instant.now(clock).plus(CHALLENGE_TTL);
        UUID id = UUID.randomUUID();
        String message = "Registerwerk wallet control proof (TFR Art. 14(5))\nentity: " + legalEntityId
                + "\nwallet: " + wallet.toLowerCase(java.util.Locale.ROOT) + "\nnonce: " + nonce
                + "\nexpires: " + expires;
        jdbc.update("""
            INSERT INTO wallet_control_proof (id, legal_entity_id, wallet_address, chain_config_id, method, status,
                                              nonce, challenge_message, expires_at)
            VALUES (?,?,?,?,'SIGNED_MESSAGE','PENDING',?,?,?)
            """, id, legalEntityId, wallet, chainConfigId, nonce, message, Timestamp.from(expires));
        return new Challenge(id, message, expires);
    }

    @Transactional
    public ProofView submitSignature(UUID proofId, String signatureHex, UUID actorId, String actorRole) {
        Map<String, Object> row = jdbc.queryForList("""
            SELECT legal_entity_id, wallet_address, chain_config_id, challenge_message, status, expires_at
              FROM wallet_control_proof WHERE id = ? AND method = 'SIGNED_MESSAGE' FOR UPDATE
            """, proofId).stream().findFirst()
                .orElseThrow(() -> new EntityNotFoundException("WalletControlProof", proofId));
        if (!"PENDING".equals(row.get("status"))) {
            throw new IllegalArgumentException("Challenge is not pending (status " + row.get("status") + ")");
        }
        Instant expires = ((Timestamp) row.get("expires_at")).toInstant();
        if (!Instant.now(clock).isBefore(expires)) {
            jdbc.update("UPDATE wallet_control_proof SET status='EXPIRED' WHERE id=?", proofId);
            throw new IllegalArgumentException("Challenge expired - request a new one");
        }
        UUID entityId = (UUID) row.get("legal_entity_id");
        String wallet = (String) row.get("wallet_address");
        requireHolderWallet(entityId, wallet);
        verifier.verifyPersonalSign((UUID) row.get("chain_config_id"), (String) row.get("challenge_message"),
                signatureHex, wallet);
        Instant now = Instant.now(clock);
        Instant validUntil = now.plus(Duration.ofDays(validityDays));
        jdbc.update("""
            UPDATE wallet_control_proof SET status='VERIFIED', signature=?, verified_at=?, verified_by=?, expires_at=?
             WHERE id=?
            """, signatureHex, Timestamp.from(now), actorId, Timestamp.from(validUntil), proofId);
        events.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.WALLET_PROOF_VERIFIED,
                "WalletControlProof", proofId, actorId, actorRole,
                Map.of("method", "SIGNED_MESSAGE", "legalEntityId", entityId.toString(), "wallet", wallet)));
        return view(proofId);
    }

    @Transactional
    public ProofView attest(UUID legalEntityId, String wallet, String evidenceNote, UUID actorId, String actorRole,
                            UUID approverId) {
        if (evidenceNote == null || evidenceNote.isBlank()) {
            throw new IllegalArgumentException("An evidence note is mandatory for an operator attestation");
        }
        if (approverId == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "An operator attestation requires a second approver");
        }
        requireHolderWallet(legalEntityId, wallet);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
            INSERT INTO wallet_control_proof (id, legal_entity_id, wallet_address, method, status, evidence_ref,
                                              verified_at, verified_by, dual_control_approver_id, expires_at)
            VALUES (?,?,?,'OPERATOR_ATTESTATION','VERIFIED',?,?,?,?,?)
            """, id, legalEntityId, wallet, evidenceNote.trim(), Timestamp.from(now), actorId, approverId,
                Timestamp.from(now.plus(Duration.ofDays(validityDays))));
        events.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.WALLET_PROOF_VERIFIED,
                "WalletControlProof", id, actorId, actorRole, approverId,
                Map.of("method", "OPERATOR_ATTESTATION", "legalEntityId", legalEntityId.toString(),
                        "wallet", wallet)));
        return view(id);
    }

    @Transactional
    public void revoke(UUID proofId, UUID actorId, String actorRole) {
        int n = jdbc.update("UPDATE wallet_control_proof SET status='REVOKED' WHERE id=? AND status IN ('VERIFIED','PENDING')",
                proofId);
        if (n == 0) {
            throw new EntityNotFoundException("WalletControlProof", proofId);
        }
        events.publishEvent(new TravelRuleControlEvent(TravelRuleControlEvent.WALLET_PROOF_REVOKED,
                "WalletControlProof", proofId, actorId, actorRole, Map.of()));
    }

    @Transactional(readOnly = true)
    public List<ProofView> list(UUID legalEntityId) {
        return jdbc.query("""
            SELECT id, legal_entity_id, wallet_address, method, status, verified_at, expires_at, evidence_ref
              FROM wallet_control_proof WHERE (?::uuid IS NULL OR legal_entity_id = ?) ORDER BY created_at DESC LIMIT 500
            """, (rs, i) -> map(rs), legalEntityId, legalEntityId);
    }

    private ProofView view(UUID id) {
        return jdbc.query("""
            SELECT id, legal_entity_id, wallet_address, method, status, verified_at, expires_at, evidence_ref
              FROM wallet_control_proof WHERE id = ?
            """, (rs, i) -> map(rs), id).get(0);
    }

    private static ProofView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp v = rs.getTimestamp("verified_at");
        Timestamp e = rs.getTimestamp("expires_at");
        return new ProofView(rs.getObject("id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                rs.getString("wallet_address"), rs.getString("method"), rs.getString("status"),
                v == null ? null : v.toInstant(), e == null ? null : e.toInstant(), rs.getString("evidence_ref"));
    }

    /** The wallet must be a registered holder wallet of exactly this legal entity (on any asset). */
    private void requireHolderWallet(UUID legalEntityId, String wallet) {
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM asset_holder WHERE investor_id = ? AND LOWER(wallet_address) = LOWER(?)
            """, Integer.class, legalEntityId, wallet);
        if (n == null || n == 0) {
            throw new IllegalArgumentException(
                    "Wallet " + wallet + " is not a registered holder wallet of legal entity " + legalEntityId);
        }
    }
}
