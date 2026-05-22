package de.makibytes.registerwerk.chain.api;

/**
 * Factory interface for creating {@link CantonLedgerEndpoint} connections.
 * Decouples callers from the DAML-backed {@link CantonClientFactory} so they
 * compile without the {@code canton} Maven profile.
 */
public interface CantonClientProvider {

    CantonLedgerEndpoint createClient(
            String ledgerApiUrl,
            String synchronizerId,
            String applicationId,
            String authToken);
}
