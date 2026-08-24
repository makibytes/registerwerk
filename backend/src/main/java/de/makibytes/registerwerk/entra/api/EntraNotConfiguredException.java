package de.makibytes.registerwerk.entra.api;

/**
 * Thrown when a directory operation is attempted while the Entra integration is disabled or
 * incompletely configured. Maps to 503 — the request was valid, the capability is not wired up.
 */
public class EntraNotConfiguredException extends RuntimeException {

    public EntraNotConfiguredException(String message) {
        super(message);
    }
}
