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
 * <p>Every screening is one remote {@code /match} call (no local dataset). Sent properties: name,
 * country, LEI and registration number for companies; name, country, birth date and nationality for
 * persons. The match threshold is {@code registerwerk.screening.match-threshold} (default 0.85).
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

    private final RestClient client;
    private final BigDecimal matchThreshold;

    OpenSanctionsAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${registerwerk.screening.open-sanctions.base-url:https://api.opensanctions.org}") String baseUrl,
            @Value("${registerwerk.screening.open-sanctions.api-key:}") String apiKey,
            @Value("${registerwerk.screening.match-threshold:0.85}") BigDecimal matchThreshold) {
        this.matchThreshold = matchThreshold;
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
    public List<ScreeningHitDto> screenEntity(ScreeningSubjectDto subject) {
        return screen(subject).hits();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ScreeningResult screen(ScreeningSubjectDto subject) {
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
        // Natural persons: birth date and nationality let the provider tell namesakes apart.
        if ("Person".equals(schema)) {
            if (subject.dateOfBirth() != null) {
                properties.put("birthDate", List.of(subject.dateOfBirth().toString()));
            }
            if (hasText(subject.nationality())) {
                properties.put("nationality", List.of(subject.nationality().toLowerCase(Locale.ROOT)));
            }
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
        return new ScreeningResult(parseHits(response, subject), dataVersionOf(response), matchThreshold);
    }

    /**
     * Best-effort provenance of the answer: a top-level {@code version} if the API reports one, else the
     * newest {@code last_change} among the matched records. Null when neither is present (e.g. a clear result).
     */
    @SuppressWarnings("unchecked")
    static String dataVersionOf(Map<String, Object> response) {
        Object version = response.get("version");
        if (version != null && !String.valueOf(version).isBlank()) {
            return String.valueOf(version);
        }
        if (response.get("responses") instanceof Map<?, ?> responses
                && responses.get("q1") instanceof Map<?, ?> query
                && query.get("results") instanceof List<?> results) {
            String newest = null;
            for (Object r : results) {
                if (r instanceof Map<?, ?> m && m.get("last_change") != null) {
                    String lc = String.valueOf(m.get("last_change"));
                    if (newest == null || lc.compareTo(newest) > 0) {
                        newest = lc;
                    }
                }
            }
            return newest != null ? "last_change:" + newest : null;
        }
        return null;
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
            if (!apiMatch && score.compareTo(matchThreshold) < 0) {
                continue;
            }
            String caption = String.valueOf(entry.getOrDefault("caption", subject.name()));
            String id = entry.get("id") != null ? String.valueOf(entry.get("id")) : null;
            hits.add(new ScreeningHitDto(
                    PROVIDER,
                    "name",
                    caption,
                    score.doubleValue(),
                    id,
                    categoryOf(entry),
                    id,
                    recordVersionOf(entry)
            ));
        }
        return hits;
    }

    /**
     * Version digest of a matched record: its {@code last_change} plus a hash over the content that makes the
     * entry what it is (properties, topics, datasets). The per-query volatile parts (score, features) are
     * excluded, so the same unchanged record always yields the same value. Null for an empty entry.
     */
    static String recordVersionOf(Map<String, Object> entry) {
        Object lastChange = entry.get("last_change");
        StringBuilder content = new StringBuilder();
        for (String key : new String[]{"properties", "topics", "datasets", "schema"}) {
            if (entry.get(key) != null) {
                content.append(key).append('=');
                canonical(entry.get(key), content);
                content.append(';');
            }
        }
        if (lastChange == null && content.length() == 0) {
            return null;
        }
        String hash = "";
        if (content.length() > 0) {
            try {
                hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return (lastChange == null ? "" : lastChange + ":") + hash;
    }

    /** Deterministic rendering: map keys sorted, list members sorted as strings (the provider does not order them). */
    private static void canonical(Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> m) {
            out.append('{');
            new java.util.TreeMap<String, Object>(m.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), Map.Entry::getValue,
                            (a, b) -> a))).forEach((k, v) -> {
                out.append(k).append(':');
                canonical(v, out);
                out.append(',');
            });
            out.append('}');
        } else if (value instanceof List<?> l) {
            out.append('[');
            l.stream().map(x -> {
                StringBuilder sb = new StringBuilder();
                canonical(x, sb);
                return sb.toString();
            }).sorted().forEach(x -> out.append(x).append(','));
            out.append(']');
        } else {
            out.append(value);
        }
    }

    /**
     * OpenSanctions entities carry a "topics" list (e.g. {@code sanction}, {@code role.pep},
     * {@code poi}, {@code crime}) summarising why the entity is on file. Not every deployment/
     * dataset populates it on match results, so this stays defensive: missing or unrecognized
     * topics fall back to {@code SANCTIONS}, preserving today's behavior rather than guessing.
     *
     * <p>Sanctions win: a record tagged both {@code sanction} and {@code role.pep} is a sanctions
     * hit (a hard legal prohibition), never a PEP one that EDD could clear.
     */
    static String categoryOf(Map<String, Object> entry) {
        Object topicsObj = entry.get("topics");
        if (!(topicsObj instanceof List<?> topics)) {
            return HitCategory.SANCTIONS.name();
        }
        for (Object t : topics) {
            String topic = String.valueOf(t).toLowerCase(Locale.ROOT);
            if (topic.contains("sanction")) {
                return HitCategory.SANCTIONS.name();
            }
        }
        for (Object t : topics) {
            String topic = String.valueOf(t).toLowerCase(Locale.ROOT);
            if (topic.startsWith("role.pep") || topic.equals("poi")) {
                return HitCategory.PEP.name();
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
