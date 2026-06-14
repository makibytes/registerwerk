package de.makibytes.registerwerk.screening.api;

/**
 * Thrown when a sanctions/PEP screening provider fails to deliver a result.
 *
 * <p>Screening must fail closed: a failed or skipped screening is <em>not</em> a
 * clear result (GwG §10 Abs. 1 Nr. 5; EU sanctions regulations require effective
 * screening before any approval). Callers record the run as {@code ERROR} and the
 * compliance gate blocks approval until a successful run exists.
 */
public class ScreeningProviderException extends RuntimeException {

    private final String provider;

    public ScreeningProviderException(String provider, String message) {
        super(message);
        this.provider = provider;
    }

    public ScreeningProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }
}
