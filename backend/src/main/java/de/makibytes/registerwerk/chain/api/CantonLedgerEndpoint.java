package de.makibytes.registerwerk.chain.api;

/**
 * Minimal interface for a connected Canton Ledger API endpoint.
 * Contains only standard-Java methods so callers outside the {@code canton} Maven profile
 * can reference the type without the {@code com.daml} bindings on the classpath.
 *
 * <p>The full {@link CantonLedgerClient} implementation provides additional DAML-typed
 * accessors and is compiled only when the {@code canton} profile is active.
 */
public interface CantonLedgerEndpoint extends AutoCloseable {

    /** Pings the participant by requesting the current ledger end. */
    String getLedgerEnd();

    String getLedgerApiUrl();

    String getApplicationId();

    String getSynchronizerId();

    @Override
    void close();
}
