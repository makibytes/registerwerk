package de.makibytes.registerwerk.travelrule.internal;

import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Travel Rule adapter for the OpenTRP / TRISA protocol.
 * Activate: REGISTERWERK_TRAVEL_RULE_PROTOCOL=TRP
 *
 * Uses mTLS transport: configure cert and key paths in application config.
 * VASP discovery via Notabene TRP directory (configurable via registerwerk.travel-rule.trp.directory-url).
 */
@Component("trpAdapter")
@ConditionalOnProperty(name = "registerwerk.travel-rule.protocol", havingValue = "TRP")
class TrpAdapter implements TravelRuleProtocolPort {

    private static final Logger log = LoggerFactory.getLogger(TrpAdapter.class);

    private final RestClient directoryClient;
    private final TravelRuleProperties.Trp config;
    private final ObjectMapper mapper;
    private final SSLContext sslContext;

    TrpAdapter(TravelRuleProperties properties, ObjectMapper mapper) {
        this.config = properties.getTrp();
        this.mapper = mapper;
        this.sslContext = loadSslContext(config.getMtlsCertPath(), config.getMtlsKeyPath());
        this.directoryClient = client(config.getDirectoryUrl(), null)
                .mutate()
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("TrpAdapter initialized, endpoint={}", config.getEndpoint());
    }

    @Override
    public String protocolName() { return "TRP"; }

    @Override
    public CompletableFuture<String> send(UUID transferId, Ivms101.TravelRuleMessage payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<VaspInfo> beneficiary = payload.beneficiaryVasp() != null
                        && payload.beneficiaryVasp().beneficiaryVasp() != null
                        ? Optional.of(new VaspInfo(
                                payload.beneficiaryVasp().beneficiaryVasp().vaspId(),
                                payload.beneficiaryVasp().beneficiaryVasp().legalName(),
                                "", ""))
                        : Optional.empty();

                String endpoint = beneficiary
                        .map(VaspInfo::endpoint)
                        .filter(e -> !e.isBlank())
                        .orElse(config.getEndpoint());

                if (endpoint == null || endpoint.isBlank()) {
                    throw new IllegalStateException("No TRP delivery endpoint is configured");
                }

                RestClient mTlsClient = client(endpoint, sslContext);
                String body = mapper.writeValueAsString(Map.of(
                        "transferId", transferId.toString(),
                        "ivms101", payload
                ));

                @SuppressWarnings("unchecked")
                Map<String, Object> response = mTlsClient.post()
                        .uri("/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);

                String msgId = response != null ? String.valueOf(response.getOrDefault("messageId", transferId)) : transferId.toString();
                log.info("TRP travel rule message sent: transferId={} messageId={}", transferId, msgId);
                return msgId;
            } catch (Exception e) {
                log.error("TRP send failed for transferId={}: {}", transferId, e.getMessage());
                throw new RuntimeException("TRP send failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Optional<VaspInfo> lookupVasp(String walletAddress) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = directoryClient.get()
                    .uri("/vasps?walletAddress={addr}", walletAddress)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !response.containsKey("did")) {
                return Optional.empty();
            }
            return Optional.of(new VaspInfo(
                    String.valueOf(response.get("did")),
                    String.valueOf(response.getOrDefault("name", "")),
                    String.valueOf(response.getOrDefault("country", "")),
                    String.valueOf(response.getOrDefault("endpoint", ""))
            ));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw directoryUnavailable(walletAddress, e);
        } catch (Exception e) {
            throw directoryUnavailable(walletAddress, e);
        }
    }

    private static ComplianceGateException directoryUnavailable(String walletAddress, Exception cause) {
        return new ComplianceGateException(
                "TRP VASP directory lookup failed for wallet " + walletAddress
                        + "; beneficiary type cannot be established safely", cause);
    }

    private static RestClient client(String endpoint, SSLContext context) {
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (context != null) {
            http.sslContext(context);
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http.build());
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(endpoint)
                .build();
    }

    private static SSLContext loadSslContext(String certificatePath, String privateKeyPath) {
        boolean hasCertificate = certificatePath != null && !certificatePath.isBlank();
        boolean hasPrivateKey = privateKeyPath != null && !privateKeyPath.isBlank();
        if (!hasCertificate && !hasPrivateKey) {
            return null;
        }
        if (hasCertificate != hasPrivateKey) {
            throw new IllegalStateException(
                    "TRP mTLS requires both a PEM certificate and a PKCS#8 PEM private key");
        }
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            Certificate certificate;
            try (var input = Files.newInputStream(Path.of(certificatePath))) {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            PrivateKey privateKey = readPkcs8PrivateKey(Path.of(privateKeyPath));
            char[] password = new char[0];
            ks.setKeyEntry("trp-client", privateKey, password, new Certificate[]{certificate});

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("TRP mTLS configuration is invalid", e);
        }
    }

    private static PrivateKey readPkcs8PrivateKey(Path path) throws Exception {
        String pem = Files.readString(path);
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new IllegalArgumentException("TRP private key must be unencrypted PKCS#8 PEM");
        }
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64));
        for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (Exception ignored) {
                // Try the next supported key algorithm.
            }
        }
        throw new IllegalArgumentException("Unsupported TRP private-key algorithm");
    }
}
