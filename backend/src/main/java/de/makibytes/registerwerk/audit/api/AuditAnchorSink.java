package de.makibytes.registerwerk.audit.api;

import java.util.Optional;

/**
 * Optional SPI: publishes daily audit-chain anchors to storage the database operator cannot rewrite
 * (object-lock bucket, RFC 3161 TSA, ...). With no bean present anchors stay in {@code
 * audit_chain_anchor} only (NOOP). The verifier compares {@link #latest()} with the chain.
 * Concrete adapter choice is parked (T6-17).
 */
public interface AuditAnchorSink {

    void publish(AuditAnchor anchor);

    /** The newest anchor held by the external store, if it can be read back. */
    Optional<AuditAnchor> latest();
}
