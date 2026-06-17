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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Travel Rule adapter for the Notabene protocol (https://notabene.id).
 * Activate: REGISTERWERK_TRAVEL_RULE_PROTOCOL=NOTABENE
 *
 * API docs: https://docs.notabene.id/reference
 */
@Component("notabeneAdapter")
@ConditionalOnProperty(name = "registerwerk.travel-rule.protocol", havingValue = "NOTABENE")
class NotabeneAdapter implements TravelRuleProtocolPort {

    private static final Logger log = LoggerFactory.getLogger(NotabeneAdapter.class);

    private final RestClient rest;
    private final ObjectMapper mapper;
    private final TravelRuleProperties.Notabene config;

    NotabeneAdapter(TravelRuleProperties properties, ObjectMapper mapper) {
        this.config = properties.getNotabene();
        this.mapper = mapper;
        this.rest = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("NotabeneAdapter initialized for vaspDid={}", config.getVaspDid());
    }

    @Override
    public String protocolName() { return "NOTABENE"; }

    @Override
    public CompletableFuture<String> send(UUID transferId, Ivms101.TravelRuleMessage payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> body = buildTransactionPayload(transferId, payload);
                @SuppressWarnings("unchecked")
                Map<String, Object> response = rest.post()
                        .uri("/tf/transaction")
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                String txId = response != null ? String.valueOf(response.get("id")) : "unknown";
                log.info("Notabene travel rule message sent: transferId={} notabeneTxId={}", transferId, txId);
                return txId;
            } catch (Exception e) {
                log.error("Notabene send failed for transferId={}: {}", transferId, e.getMessage());
                throw new RuntimeException("Notabene send failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Optional<VaspInfo> lookupVasp(String walletAddress) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.get()
                    .uri("/tf/vasp/search?address={addr}", walletAddress)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new VaspInfo(
                    String.valueOf(response.getOrDefault("did", "")),
                    String.valueOf(response.getOrDefault("name", "")),
                    String.valueOf(response.getOrDefault("jurisdictionCountry", "")),
                    String.valueOf(response.getOrDefault("addressUri", ""))
            ));
        } catch (Exception e) {
            log.debug("Notabene VASP lookup for address={} returned no result: {}", walletAddress, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> buildTransactionPayload(UUID transferId, Ivms101.TravelRuleMessage msg) {
        Map<String, Object> body = new HashMap<>();
        body.put("transactionAsset", "ETH");
        body.put("transactionAmount", "0");
        body.put("notificationEmail", "");
        body.put("originatorDid", config.getVaspDid());

        if (msg.beneficiaryVasp() != null && msg.beneficiaryVasp().beneficiaryVasp() != null) {
            body.put("beneficiaryDid", msg.beneficiaryVasp().beneficiaryVasp().vaspId());
        }

        try {
            body.put("ivms101", mapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.warn("Failed to serialize IVMS-101 payload for transferId={}", transferId);
        }

        body.put("transactionRef", transferId.toString());
        return body;
    }
}
