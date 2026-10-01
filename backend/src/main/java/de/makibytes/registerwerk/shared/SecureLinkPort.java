package de.makibytes.registerwerk.shared;

import java.util.UUID;

/**
 * Seals one-time links (registration/reset tokens) before they are placed in a published event, so the
 * plaintext token never lands in {@code event_publication.serialized_event} (7A-04). Envelope-encrypted
 * under the platform KEK; sealing fails (no plaintext fallback) when no KEK is usable.
 */
public interface SecureLinkPort {
    String seal(String link, UUID userId);
    String open(String sealed, UUID userId);
}
