package de.makibytes.registerwerk.finality.api;

import java.util.UUID;

/**
 * Signals that chain-derived mutation is intentionally parked behind an active safety quarantine.
 * Durable consumers must not acknowledge the triggering item: it remains replayable after an
 * explicit operator resolution.
 */
public class ChainQuarantinedException extends RuntimeException {

    private final UUID chainConfigId;

    public ChainQuarantinedException(UUID chainConfigId) {
        super("Chain is quarantined pending explicit operator resolution: " + chainConfigId);
        this.chainConfigId = chainConfigId;
    }

    public UUID chainConfigId() {
        return chainConfigId;
    }
}
