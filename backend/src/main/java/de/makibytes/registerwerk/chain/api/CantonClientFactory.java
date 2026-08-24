package de.makibytes.registerwerk.chain.api;

// CantonLedgerEndpoint is in the same package — no import needed
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

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
     * @param ledgerApiUrl  participant Ledger API endpoint. Use {@code grpcs://host:port} for
     *                      TLS or {@code grpc://host:port} for explicitly plaintext development.
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

        Endpoint endpoint = Endpoint.parse(ledgerApiUrl);

        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder
                .forAddress(endpoint.host(), endpoint.port())
                .maxInboundMessageSize(64 * 1024 * 1024); // 64 MB

        if (StringUtils.hasText(authToken)) {
            String token = authToken.trim();
            if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Canton auth token must not contain line breaks");
            }
            Metadata headers = new Metadata();
            headers.put(
                    Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + token);
            channelBuilder.intercept(MetadataUtils.newAttachHeadersInterceptor(headers));
        }

        if (endpoint.tls()) {
            channelBuilder.useTransportSecurity();
        } else {
            channelBuilder.usePlaintext();
        }

        ManagedChannel channel = channelBuilder.build();

        log.info("Canton Ledger API client configured: {}:{} transport={} app={} synchronizer={}",
                endpoint.host(), endpoint.port(), endpoint.tls() ? "TLS" : "PLAINTEXT",
                applicationId, synchronizerId);

        return new CantonLedgerClient(channel, applicationId, synchronizerId, ledgerApiUrl);
    }

    record Endpoint(String host, int port, boolean tls) {

        static Endpoint parse(String value) {
            if (!StringUtils.hasText(value)) {
                throw new IllegalArgumentException("Canton Ledger API URL is required");
            }

            String input = value.trim();
            boolean explicitScheme = input.contains("://");
            URI uri;
            try {
                uri = new URI(explicitScheme ? input : "grpc://" + input);
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException("Invalid Canton Ledger API URL: " + value, ex);
            }

            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean tls = switch (scheme) {
                case "grpcs", "https" -> true;
                case "grpc" -> false;
                default -> throw new IllegalArgumentException(
                        "Canton Ledger API URL scheme must be grpcs:// (TLS) or grpc:// (plaintext)");
            };

            if (!StringUtils.hasText(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException(
                        "Canton Ledger API URL must contain only a host and optional port");
            }

            int port = uri.getPort() >= 0 ? uri.getPort() : (tls ? 443 : 5001);
            if (!explicitScheme) {
                log.warn("Scheme-less Canton Ledger API endpoint '{}' is treated as plaintext; "
                        + "use grpcs:// for production or grpc:// to make development plaintext explicit",
                        input);
            }
            return new Endpoint(uri.getHost(), port, tls);
        }
    }
}
