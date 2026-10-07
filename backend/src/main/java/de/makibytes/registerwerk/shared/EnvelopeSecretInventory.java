package de.makibytes.registerwerk.shared;

import java.util.Map;

/**
 * Implemented by every module that stores {@link EnvelopeCipher} values under the platform KEK, so that the
 * wallet module can report which KEK version each ciphertext is on, re-wrap them, and refuse to retire a KEK
 * version that is still referenced - without depending on those modules.
 */
public interface EnvelopeSecretInventory {

    /** Stable upper-case name used as metric label and in audit payloads, e.g. {@code TOTP_SECRET}. */
    String type();

    /** Number of stored ciphertexts per KEK version label ({@code unknown} = cannot be attributed). */
    Map<String, Long> countByKekVersion();

    /**
     * Re-wraps every ciphertext that is not on the active KEK version, in pages of {@code pageSize}. Idempotent;
     * a row that fails is counted and skipped, never aborts the run, and never logs secret material.
     */
    RewrapOutcome rewrapStale(int pageSize);

    record RewrapOutcome(int rewrapped, int failed) { }
}
