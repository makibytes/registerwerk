package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * OpenSanctions API adapter — covers OFAC SDN, EU CFSP, UN 1267, UK HMT, CH-SECO lists.
 * Free tier: https://api.opensanctions.org. Commercial: self-hosted or SaaS key.
 * Set OPENSANCTIONS_API_KEY or leave blank for the free public API (rate-limited).
 */
@Component
@ConditionalOnProperty(name = "registerwerk.screening.open-sanctions.enabled", havingValue = "true", matchIfMissing = true)
class OpenSanctionsAdapter implements SanctionsScreeningPort {

    private static final Logger log = LoggerFactory.getLogger(OpenSanctionsAdapter.class);
    private static final BigDecimal MATCH_THRESHOLD = new BigDecimal("0.85");

    private final RestClient client;

    OpenSanctionsAdapter(
            @Value("${registerwerk.screening.open-sanctions.base-url:https://api.opensanctions.org}") String baseUrl,
            @Value("${registerwerk.screening.open-sanctions.api-key:}") String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "ApiKey " + apiKey);
        }
        this.client = builder.build();
    }

    @Override
    public String providerName() {
        return "OPEN_SANCTIONS";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ScreeningHitDto> screenEntity(ScreeningSubjectDto subject) {
        try {
            var response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/match/default")
                            .queryParam("algorithm", "best")
                            .build())
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("results")) {
                return Collections.emptyList();
            }

            var results = (List<Map<String, Object>>) response.get("results");
            return results.stream()
                    .filter(r -> {
                        Object score = r.get("score");
                        if (!(score instanceof Number n)) return false;
                        return new BigDecimal(n.toString()).compareTo(MATCH_THRESHOLD) >= 0;
                    })
                    .map(r -> new ScreeningHitDto(
                            "OPEN_SANCTIONS",
                            "name",
                            subject.name(),
                            new BigDecimal(r.getOrDefault("score", "0").toString()).doubleValue(),
                            String.valueOf(r.get("id"))
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("OpenSanctions screening failed for subject {}: {}", subject.subjectId(), e.getMessage());
            return Collections.emptyList();
        }
    }
}
