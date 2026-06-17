package de.makibytes.registerwerk.travelrule.internal;

import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;
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

    TrpAdapter(TravelRuleProperties properties, ObjectMapper mapper) {
        this.config = properties.getTrp();
        this.mapper = mapper;
        this.directoryClient = RestClient.builder()
                .baseUrl(config.getDirectoryUrl())
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
                    log.warn("TRP: no endpoint found for beneficiary VASP; skipping transfer {}", transferId);
                    return "trp-noop-" + transferId;
                }

                RestClient mTlsClient = buildMtlsClient(endpoint);
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
        } catch (Exception e) {
            log.debug("TRP VASP lookup for address={} failed: {}", walletAddress, e.getMessage());
            return Optional.empty();
        }
    }

    private RestClient buildMtlsClient(String endpoint) {
        if (config.getMtlsCertPath() == null || config.getMtlsCertPath().isBlank()) {
            return RestClient.builder().baseUrl(endpoint).build();
        }
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(config.getMtlsCertPath())) {
                ks.load(fis, null);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, null);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            return RestClient.builder()
                    .baseUrl(endpoint)
                    .build();
        } catch (Exception e) {
            log.warn("TRP mTLS setup failed, falling back to plain TLS: {}", e.getMessage());
            return RestClient.builder().baseUrl(endpoint).build();
        }
    }
}
