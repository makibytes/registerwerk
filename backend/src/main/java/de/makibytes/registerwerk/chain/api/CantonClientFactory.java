package de.makibytes.registerwerk.chain.api;

import com.daml.ledger.rxjava.DamlLedgerClient;
// CantonLedgerEndpoint is in the same package — no import needed
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Factory that creates {@link CantonLedgerClient} instances from participant connection settings.
 * Mirrors {@code SolanaClientFactory} and {@code Web3jClientFactory} in structure.
 */
@Component
public class CantonClientFactory implements CantonClientProvider {

    private static final Logger log = LoggerFactory.getLogger(CantonClientFactory.class);

    /**
     * Creates a new {@link CantonLedgerClient} connected to the given participant endpoint.
     *
     * @param ledgerApiUrl  host:port of the participant's Ledger API gRPC endpoint
     * @param synchronizerId Canton synchronizer alias (used in command submissions)
     * @param applicationId  application ID registered with the participant
     * @param authToken      optional JWT bearer token for authentication (null or blank = no auth)
     */
    @Override
    public CantonLedgerEndpoint createClient(
            String ledgerApiUrl,
            String synchronizerId,
            String applicationId,
            String authToken) {

        String host = extractHost(ledgerApiUrl);
        int    port = extractPort(ledgerApiUrl);

        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder
                .forAddress(host, port)
                .maxInboundMessageSize(64 * 1024 * 1024); // 64 MB

        if (StringUtils.hasText(authToken)) {
            Metadata headers = new Metadata();
            headers.put(
                    Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + authToken);
            channelBuilder.intercept(MetadataUtils.newAttachHeadersInterceptor(headers));
        }

        // Use plaintext for dev (no-TLS); operators enable TLS via participant config or via
        // a TLS-terminating proxy. For production, replace usePlaintext() with useTransportSecurity()
        // and supply the participant's TLS certificate.
        channelBuilder.usePlaintext();

        ManagedChannel channel = channelBuilder.build();

        DamlLedgerClient damlClient = DamlLedgerClient.newBuilder(channel).build();
        damlClient.connect();

        log.info("Canton Ledger API client connected: {}  app={} synchronizer={}",
                ledgerApiUrl, applicationId, synchronizerId);

        return new CantonLedgerClient(damlClient, channel, applicationId, synchronizerId, ledgerApiUrl);
    }

    private static String extractHost(String ledgerApiUrl) {
        int colon = ledgerApiUrl.lastIndexOf(':');
        return colon > 0 ? ledgerApiUrl.substring(0, colon) : ledgerApiUrl;
    }

    private static int extractPort(String ledgerApiUrl) {
        int colon = ledgerApiUrl.lastIndexOf(':');
        if (colon > 0 && colon < ledgerApiUrl.length() - 1) {
            try {
                return Integer.parseInt(ledgerApiUrl.substring(colon + 1));
            } catch (NumberFormatException ignored) {}
        }
        return 5001;
    }
}
