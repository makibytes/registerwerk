package de.makibytes.registerwerk.audit.internal;

import de.makibytes.registerwerk.audit.api.SigningKeyProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Locks the single {@code audit_chain_tip} row for the duration of the caller's
 * transaction, serializing hash-chain appends across threads AND backend instances so
 * the chain can never fork under concurrent/multi-instance load. Reserves the next
 * {@code audit_event_seq} value explicitly so the sequence number is known before the
 * hash is computed, then advances the tip to the new row's entry_hash before the lock is
 * released at commit.
 *
 * <p>Shared by every writer of {@code audit_event} — {@link AuditEventRecorder} (normal
 * domain events) and {@link AuditFailureRecorder} (rejected/blocked action attempts) —
 * so all rows participate in one continuous chain regardless of which path wrote them.
 *
 * <p>{@code entry_sig} (Ed25519 signature over entry_hash) is populated only when a
 * {@link SigningKeyProvider} bean is present (opt-in — see that interface's javadoc for why
 * this isn't unconditionally forced on the way {@code wallet.internal.EnvVarKekProvider} is).
 */
@Component
class AuditChainAppender {

    private final JdbcTemplate jdbc;
    private final AuditCanonicalJson canonicalJson;
    private final Optional<SigningKeyProvider> signingKeyProvider;

    AuditChainAppender(JdbcTemplate jdbc, AuditCanonicalJson canonicalJson,
                       Optional<SigningKeyProvider> signingKeyProvider) {
        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
        this.signingKeyProvider = signingKeyProvider;
    }

    void append(AuditEvent ae) {
        byte[] prevHash = jdbc.queryForObject(
                "SELECT entry_hash FROM audit_chain_tip WHERE id = TRUE FOR UPDATE", byte[].class);
        Long seq = jdbc.queryForObject("SELECT nextval('audit_event_seq')", Long.class);
        String canonical = canonicalJson.canonicalize(
                ae.getEventType(), ae.getSubjectType(), ae.getSubjectId(), ae.getPayload());
        byte[] entryHash = AuditChainVerificationService.sha256(prevHash, canonical, seq);

        ae.setSequenceNo(seq);
        ae.setPrevHash(prevHash);
        ae.setEntryHash(entryHash);
        signingKeyProvider.ifPresent(provider -> ae.setEntrySig(provider.sign(entryHash)));

        jdbc.update("UPDATE audit_chain_tip SET entry_hash = ? WHERE id = TRUE", (Object) entryHash);
    }
}
