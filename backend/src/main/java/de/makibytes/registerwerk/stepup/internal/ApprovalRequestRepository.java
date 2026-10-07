package de.makibytes.registerwerk.stepup.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage of the approval queue. Every state change is ONE conditional {@code UPDATE ... WHERE status = ...}: of
 * N concurrent decisions/claims exactly one sees the expected status and updates the row, the others update
 * nothing. No read-modify-write, no application-level locking.
 */
@Repository
class ApprovalRequestRepository {

    private static final String SELECT = """
            SELECT r.id, r.requester_user_id, rq.email AS requester_email, r.action, r.method, r.path, r.query,
                   r.canonical_body, r.target_digest, r.status, r.approver_user_id, ap.email AS approver_email,
                   r.created_at, r.expires_at, r.decided_at, r.decision_note, r.claimed_at
              FROM approval_request r
              LEFT JOIN app_user rq ON rq.id = r.requester_user_id
              LEFT JOIN app_user ap ON ap.id = r.approver_user_id
            """;

    private static final RowMapper<ApprovalRequestRecord> MAPPER = ApprovalRequestRepository::map;

    private final JdbcTemplate jdbc;

    ApprovalRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ApprovalRequestRecord map(ResultSet rs, int row) throws SQLException {
        return new ApprovalRequestRecord(
                rs.getObject("id", UUID.class), rs.getObject("requester_user_id", UUID.class),
                rs.getString("requester_email"), rs.getString("action"), rs.getString("method"),
                rs.getString("path"), rs.getString("query"), rs.getString("canonical_body"),
                rs.getString("target_digest"), rs.getString("status"), rs.getObject("approver_user_id", UUID.class),
                rs.getString("approver_email"), instant(rs, "created_at"), instant(rs, "expires_at"),
                instant(rs, "decided_at"), rs.getString("decision_note"), instant(rs, "claimed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    void insert(UUID id, UUID requester, String action, String method, String path, String query,
                String canonicalBody, String digest, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO approval_request (id, requester_user_id, action, method, path, query, canonical_body,
                                              target_digest, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, id, requester, action, method, path, query, canonicalBody, digest, Timestamp.from(expiresAt));
    }

    Optional<ApprovalRequestRecord> find(UUID id) {
        return jdbc.query(SELECT + " WHERE r.id = ?", MAPPER, id).stream().findFirst();
    }

    /** Requests the requester still holds open (waiting for a decision, or approved and not yet claimed). */
    int countOpen(UUID requester) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM approval_request
                 WHERE requester_user_id = ? AND status IN ('PENDING', 'APPROVED') AND expires_at > now()
                """, Integer.class, requester);
        return n == null ? 0 : n;
    }

    /** PENDING, unexpired requests of other people, oldest first. */
    List<ApprovalRequestRecord> pending(UUID approver, int limit, long offset) {
        return jdbc.query(SELECT + """
                 WHERE r.status = 'PENDING' AND r.expires_at > now() AND r.requester_user_id <> ?
                 ORDER BY r.created_at ASC, r.id ASC LIMIT ? OFFSET ?
                """, MAPPER, approver, limit, offset);
    }

    long countPending(UUID approver) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM approval_request
                 WHERE status = 'PENDING' AND expires_at > now() AND requester_user_id <> ?
                """, Long.class, approver);
        return n == null ? 0 : n;
    }

    List<ApprovalRequestRecord> mine(UUID requester, int limit, long offset) {
        return jdbc.query(SELECT + """
                 WHERE r.requester_user_id = ?
                 ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?
                """, MAPPER, requester, limit, offset);
    }

    long countMine(UUID requester) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM approval_request WHERE requester_user_id = ?",
                Long.class, requester);
        return n == null ? 0 : n;
    }

    /** PENDING -> APPROVED / REJECTED by someone other than the requester, before expiry. */
    boolean decide(UUID id, UUID approver, String newStatus, String note) {
        return jdbc.update("""
                UPDATE approval_request
                   SET status = ?, approver_user_id = ?, decided_at = now(), decision_note = ?
                 WHERE id = ? AND status = 'PENDING' AND expires_at > now() AND requester_user_id <> ?
                """, newStatus, approver, note, id, approver) == 1;
    }

    /** PENDING / APPROVED -> CANCELLED by the requester; a CLAIMED approval is already spent. */
    boolean cancel(UUID id, UUID requester) {
        return jdbc.update("""
                UPDATE approval_request SET status = 'CANCELLED', decided_at = COALESCE(decided_at, now())
                 WHERE id = ? AND requester_user_id = ? AND status IN ('PENDING', 'APPROVED')
                """, id, requester) == 1;
    }

    /** APPROVED -> CLAIMED by the requester, before expiry; the single place a token can come from. */
    boolean claim(UUID id, UUID requester, String jti) {
        return jdbc.update("""
                UPDATE approval_request SET status = 'CLAIMED', claimed_at = now(), claim_jti = ?
                 WHERE id = ? AND requester_user_id = ? AND status = 'APPROVED' AND expires_at > now()
                   AND approver_user_id IS NOT NULL AND approver_user_id <> requester_user_id
                """, jti, id, requester) == 1;
    }

    record Expired(UUID id, UUID requesterUserId, UUID approverUserId, String action, String method, String path,
                   String targetDigest, String previousStatus) {}

    /** Marks every stale PENDING/APPROVED request EXPIRED; rows locked by a concurrent decision are skipped. */
    List<Expired> expireStale() {
        return jdbc.query("""
                WITH stale AS (
                    SELECT id, status AS previous_status FROM approval_request
                     WHERE status IN ('PENDING', 'APPROVED') AND expires_at <= now()
                       FOR UPDATE SKIP LOCKED)
                UPDATE approval_request r SET status = 'EXPIRED'
                  FROM stale WHERE r.id = stale.id
                RETURNING r.id, r.requester_user_id, r.approver_user_id, r.action, r.method, r.path,
                          r.target_digest, stale.previous_status
                """, (rs, i) -> new Expired(rs.getObject("id", UUID.class), rs.getObject("requester_user_id", UUID.class),
                rs.getObject("approver_user_id", UUID.class), rs.getString("action"), rs.getString("method"),
                rs.getString("path"), rs.getString("target_digest"), rs.getString("previous_status")));
    }
}
