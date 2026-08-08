package de.makibytes.registerwerk.screening.internal;

import de.makibytes.registerwerk.screening.api.SanctionsScreeningPort;
import de.makibytes.registerwerk.screening.api.ScreeningProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenSanctions API adapter — covers OFAC SDN, EU CFSP, UN 1267, UK HMT, CH-SECO lists.
 * Free tier: https://api.opensanctions.org. Commercial: self-hosted or SaaS key.
 * Set OPENSANCTIONS_API_KEY or leave blank for the free public API (rate-limited).
 *
 * <p><strong>Fail-closed:</strong> any transport, authentication, or API error is
 * surfaced as {@link ScreeningProviderException} so the screening run is recorded
 * as {@code ERROR} — never silently treated as CLEAR. A screening that did not run
 * is not a clear result (GwG §10 Abs. 1 Nr. 5).
 */
@Component
@ConditionalOnProperty(name = "registerwerk.screening.open-sanctions.enabled", havingValue = "true", matchIfMissing = true)
class OpenSanctionsAdapter implements SanctionsScreeningPort {

    private static final Logger log = LoggerFactory.getLogger(OpenSanctionsAdapter.class);
    private static final String PROVIDER = "OPEN_SANCTIONS";
    private static final BigDecimal MATCH_THRESHOLD = new BigDecimal("0.85");

    private final RestClient client;

    OpenSanctionsAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${registerwerk.screening.open-sanctions.base-url:https://api.opensanctions.org}") String baseUrl,
            @Value("${registerwerk.screening.open-sanctions.api-key:}") String apiKey) {
        RestClient.Builder builder = restClientBuilder.baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "ApiKey " + apiKey);
        }
        this.client = builder.build();
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ScreeningHitDto> screenEntity(ScreeningSubjectDto subject) {
        if (subject.name() == null || subject.name().isBlank()) {
            throw new ScreeningProviderException(PROVIDER,
                    "Cannot screen subject " + subject.subjectId() + ": name is blank");
        }

        // OpenSanctions matching requires a POST with the subject's properties —
        // a query without a name matches nothing and would screen nobody.
        String schema = "NATURAL_PERSON".equalsIgnoreCase(subject.subjectType()) ? "Person" : "Company";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", List.of(subject.name()));
        if (hasText(subject.countryCode())) {
            properties.put("country", List.of(subject.countryCode().toLowerCase(Locale.ROOT)));
        }
        if (hasText(subject.lei())) {
            properties.put("leiCode", List.of(subject.lei()));
        }
        if (hasText(subject.registrationNumber())) {
            properties.put("registrationNumber", List.of(subject.registrationNumber()));
        }
        Map<String, Object> requestBody = Map.of(
                "queries", Map.of("q1", Map.of("schema", schema, "properties", properties)));

        Map<String, Object> response;
        try {
            response = client.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/match/default")
                            .queryParam("algorithm", "best")
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.error("OpenSanctions screening failed for subject {}: {}", subject.subjectId(), e.getMessage());
            throw new ScreeningProviderException(PROVIDER,
                    "OpenSanctions request failed for subject " + subject.subjectId() + ": " + e.getMessage(), e);
        }

        if (response == null) {
            throw new ScreeningProviderException(PROVIDER,
                    "OpenSanctions returned an empty response for subject " + subject.subjectId());
        }
        return parseHits(response, subject);
    }

    @SuppressWarnings("unchecked")
    private List<ScreeningHitDto> parseHits(Map<String, Object> response, ScreeningSubjectDto subject) {
        Object responsesObj = response.get("responses");
        if (!(responsesObj instanceof Map<?, ?> responses)) {
            throw new ScreeningProviderException(PROVIDER,
                    "OpenSanctions response is missing 'responses' for subject " + subject.subjectId());
        }
        Object queryObj = responses.get("q1");
        if (!(queryObj instanceof Map<?, ?> query)) {
            throw new ScreeningProviderException(PROVIDER,
                    "OpenSanctions response is missing query result for subject " + subject.subjectId());
        }
        Object resultsObj = ((Map<String, Object>) query).get("results");
        if (!(resultsObj instanceof List<?> results)) {
            return List.of();
        }

        List<ScreeningHitDto> hits = new ArrayList<>();
        for (Object r : results) {
            if (!(r instanceof Map<?, ?> result)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) result;
            BigDecimal score = toScore(entry.get("score"));
            boolean apiMatch = Boolean.TRUE.equals(entry.get("match"));
            if (!apiMatch && score.compareTo(MATCH_THRESHOLD) < 0) {
                continue;
            }
            String caption = String.valueOf(entry.getOrDefault("caption", subject.name()));
            hits.add(new ScreeningHitDto(
                    PROVIDER,
                    "name",
                    caption,
                    score.doubleValue(),
                    String.valueOf(entry.get("id")),
                    categoryOf(entry)
            ));
        }
        return hits;
    }

    /**
     * OpenSanctions entities carry a "topics" list (e.g. {@code sanction}, {@code role.pep},
     * {@code poi}, {@code crime}) summarising why the entity is on file. Not every deployment/
     * dataset populates it on match results, so this stays defensive: missing or unrecognized
     * topics fall back to {@code SANCTIONS}, preserving today's behavior rather than guessing.
     */
    @SuppressWarnings("unchecked")
    static String categoryOf(Map<String, Object> entry) {
        Object topicsObj = entry.get("topics");
        if (!(topicsObj instanceof List<?> topics)) {
            return HitCategory.SANCTIONS.name();
        }
        for (Object t : topics) {
            String topic = String.valueOf(t).toLowerCase(Locale.ROOT);
            if (topic.startsWith("role.pep") || topic.equals("poi")) {
                return HitCategory.PEP.name();
            }
        }
        for (Object t : topics) {
            String topic = String.valueOf(t).toLowerCase(Locale.ROOT);
            if (topic.contains("sanction")) {
                return HitCategory.SANCTIONS.name();
            }
        }
        if (!topics.isEmpty()) {
            return HitCategory.ADVERSE_MEDIA.name();
        }
        return HitCategory.SANCTIONS.name();
    }

    private static BigDecimal toScore(Object score) {
        if (score instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return BigDecimal.ZERO;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
