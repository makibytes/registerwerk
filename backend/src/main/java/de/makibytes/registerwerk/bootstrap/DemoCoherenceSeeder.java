package de.makibytes.registerwerk.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes the demo data tell a coherent story once the production gates exist (Wave 5b). The base seeders
 * create APPROVED entities, ISSUED assets and registered holder wallets directly in their terminal state, so
 * the evidence the gates and screens now ask for was missing:
 * <ul>
 *   <li>a {@code kyc_approval_record} (decision history, two approvers) for every APPROVED entity;</li>
 *   <li>a public term sheet (an inline {@code TERM_SHEET} document) for every ISSUED / SUSPENDED / REDEEMED asset;</li>
 *   <li>a VERIFIED operator-attested {@code wallet_control_proof} for every registered holder wallet
 *       (Travel Rule Art. 14(5) TFR gate for self-hosted wallets).</li>
 * </ul>
 * Every row is marked {@code DEMO-SEED} and nothing here is real evidence. Pure SQL against rows that already
 * exist, "insert where missing", so it is idempotent and repairs an already-seeded database. Runs after every other
 * seeder; exists only with {@code registerwerk.seed-demo-data=true} (and, as a {@link de.makibytes.registerwerk.shared.DemoOnly},
 * is refused by the production readiness check).
 */
@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
public class DemoCoherenceSeeder implements ApplicationRunner, Ordered, de.makibytes.registerwerk.shared.DemoOnly {

    private static final Logger log = LoggerFactory.getLogger(DemoCoherenceSeeder.class);

    static final String MARKER = "DEMO-SEED";
    static final String SECOND_APPROVER_EMAIL = "dual-control.admin@registerwerk-demo.internal";
    private static final long PROOF_VALIDITY_DAYS = 365;

    private final JdbcTemplate jdbc;

    public DemoCoherenceSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int[] done = seed();
        if (done[0] + done[1] + done[2] > 0) {
            log.info("Demo coherence: {} KYC approval record(s), {} term sheet(s), {} wallet-control proof(s) seeded.",
                    done[0], done[1], done[2]);
        }
    }

    /** @return {kycApprovalRecords, termSheets, walletControlProofs} newly written */
    int[] seed() {
        UUID second = firstId("SELECT id FROM app_user WHERE lower(email) = lower(?)", SECOND_APPROVER_EMAIL);
        UUID first = firstId("""
                SELECT u.id FROM app_user u JOIN app_user_role r ON r.app_user_id = u.id
                 WHERE r.role = 'REGISTRY_ADMIN' AND u.enabled AND (?::uuid IS NULL OR u.id <> ?::uuid)
                 ORDER BY u.created_at LIMIT 1""", second, second);
        return new int[] {kycApprovalRecords(first, second), termSheets(first), walletControlProofs(first, second)};
    }

    private int kycApprovalRecords(UUID approver, UUID second) {
        return jdbc.update("""
                INSERT INTO kyc_approval_record (id, entity_id, approved_by, second_approver_id, jurisdiction,
                                                 expiry_date, checklist_compliant, identified_pct, smo_fallback,
                                                 evidence_snapshot, created_at)
                SELECT gen_random_uuid(), e.id, ?, ?, 'DE_EWPG', COALESCE(e.kyc_expiry_date, current_date + 365),
                       true, 100, false,
                       'DEMO-SEED: demonstration approval, not a real KYC decision (no evidence was reviewed)', now()
                  FROM legal_entity e
                 WHERE e.kyc_status = 'APPROVED'
                   AND NOT EXISTS (SELECT 1 FROM kyc_approval_record r WHERE r.entity_id = e.id)
                """, approver, second);
    }

    private int termSheets(UUID uploader) {
        List<Map<String, Object>> assets = jdbc.queryForList("""
                SELECT a.id, a.asset_number, a.name, a.isin, a.token_standard, a.status
                  FROM asset a
                 WHERE a.status IN ('ISSUED','SUSPENDED','REDEMPTION_PENDING','REDEEMED')
                   AND NOT EXISTS (SELECT 1 FROM asset_document d
                                    WHERE d.asset_id = a.id AND d.document_type = 'TERM_SHEET'
                                      AND d.deleted_at IS NULL AND d.superseded_by IS NULL)
                 ORDER BY a.asset_number""");
        for (Map<String, Object> a : assets) {
            byte[] content = termSheetText(a).getBytes(StandardCharsets.UTF_8);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO asset_document (id, asset_id, document_type, source, mime_type, file_name, storage_ref,
                                                content_hash, size_bytes, uploaded_by)
                    VALUES (?,?,'TERM_SHEET','UPLOAD','text/plain',?,'inline',?,?,?)
                    """, id, a.get("id"), "term-sheet-" + a.get("asset_number") + ".txt", sha256Hex(content),
                    (long) content.length, uploader);
            jdbc.update("INSERT INTO asset_document_content (id, content) VALUES (?,?)", id, content);
        }
        return assets.size();
    }

    private static String termSheetText(Map<String, Object> a) {
        return String.join("\n",
                "TERM SHEET (" + MARKER + ")",
                "",
                "Instrument:     " + a.get("name"),
                "Asset number:   " + a.get("asset_number"),
                "ISIN:           " + (a.get("isin") != null ? a.get("isin") : "n/a"),
                "Token standard: " + a.get("token_standard"),
                "",
                "This document is demonstration content generated by the demo data seeder. It is not an offering",
                "document, not reviewed by anyone and has no legal effect.",
                "");
    }

    private int walletControlProofs(UUID actor, UUID approver) {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expires = Timestamp.from(Instant.now().plus(PROOF_VALIDITY_DAYS, ChronoUnit.DAYS));
        return jdbc.update("""
                INSERT INTO wallet_control_proof (id, legal_entity_id, wallet_address, method, status, evidence_ref,
                                                  verified_at, verified_by, dual_control_approver_id, expires_at)
                SELECT gen_random_uuid(), h.investor_id, h.wallet_address, 'OPERATOR_ATTESTATION', 'VERIFIED',
                       'DEMO-SEED: demonstration attestation, no control of the wallet was proven', ?, ?, ?, ?
                  FROM (SELECT DISTINCT investor_id, wallet_address FROM asset_holder) h
                  JOIN legal_entity e ON e.id = h.investor_id
                 WHERE e.kyc_status = 'APPROVED'
                   AND NOT EXISTS (
                       SELECT 1 FROM wallet_control_proof p
                        WHERE p.legal_entity_id = h.investor_id AND lower(p.wallet_address) = lower(h.wallet_address)
                          AND p.status = 'VERIFIED' AND (p.expires_at IS NULL OR p.expires_at > now()))
                """, now, actor, approver, expires);
    }

    private UUID firstId(String sql, Object... args) {
        List<UUID> ids = jdbc.queryForList(sql, UUID.class, args);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
