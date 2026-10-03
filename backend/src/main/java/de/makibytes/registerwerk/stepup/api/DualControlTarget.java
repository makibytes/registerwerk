package de.makibytes.registerwerk.stepup.api;

import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a dual-control approval is bound to (K3, 6-08). The approver's token carries
 * {@code stepup_target = base64url(sha256(canonical request))}; the live request is digested the same
 * way and the two must be equal, so an approval for "burn 5 tokens of X" cannot be replayed as "burn
 * everything of Y" even though both share the {@code FORCE_BURN_EWG26} reason.
 *
 * <p>Canonical request, UTF-8, newline separated:
 * <pre>
 * v1
 * METHOD                      (upper case)
 * /path                       (request path without context path or query; no trailing slash; case preserved)
 * a=1&amp;b=2                     (raw query string, pairs sorted; empty when none)
 * hex(sha256(canonicalJson))  (only for body-bound reasons; empty otherwise)
 * </pre>
 * Canonical JSON: object keys sorted, no whitespace, strings JSON-escaped, numbers in plain
 * decimal form without trailing zeros. A client reproduces it with any JSON library.
 *
 * <p>Nothing that is ambiguous is ever bound (C2): a JSON document with a repeated object key, a number
 * that does not parse as an exact decimal, or a query string that repeats a parameter name throws
 * {@link IllegalArgumentException}. Numbers are parsed as {@link BigDecimal} from the text, never through a
 * {@code double}, so two amounts that differ only beyond double precision never share a digest.
 */
public final class DualControlTarget {

    /** Beyond this many digits or this exponent a number is refused rather than expanded to plain form. */
    private static final int MAX_NUMBER_MAGNITUDE = 1_000;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /**
     * Same strictness for a value read out of a larger document (the approver's {@code targetBody}): there the
     * next token is a sibling field, so "trailing tokens" must not be an error.
     */
    private static final JsonMapper EMBEDDED_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private DualControlTarget() {}

    /** @param canonicalBodyJson output of {@link #canonicalJson}, or null when the reason is not body-bound */
    public static String digest(String method, String path, String rawQuery, String canonicalBodyJson) {
        String bodyHash = canonicalBodyJson == null ? "" : hex(sha256(canonicalBodyJson));
        String canonical = "v1\n" + method.toUpperCase(Locale.ROOT) + "\n" + normalizePath(path) + "\n"
                + normalizeQuery(rawQuery) + "\n" + bodyHash;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(canonical));
    }

    /** Digest of a {@code "METHOD /path?query"} target string as sent by an approver's client. */
    public static String digestOfTarget(String target, String canonicalBodyJson) {
        if (target == null) {
            throw new IllegalArgumentException("target must be 'METHOD /path'");
        }
        String t = target.trim();
        int space = t.indexOf(' ');
        if (space <= 0 || space == t.length() - 1) {
            throw new IllegalArgumentException("target must be 'METHOD /path'");
        }
        String method = t.substring(0, space);
        String rest = t.substring(space + 1).trim();
        if (!rest.startsWith("/")) {
            throw new IllegalArgumentException("target path must start with '/'");
        }
        int q = rest.indexOf('?');
        return digest(method, q < 0 ? rest : rest.substring(0, q), q < 0 ? "" : rest.substring(q + 1),
                canonicalBodyJson);
    }

    public static String normalizePath(String path) {
        String p = path == null || path.isEmpty() ? "/" : path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(List.of(rawQuery.split("&")));
        parts.removeIf(String::isEmpty);
        parts.sort(String::compareTo);
        // A repeated name is ambiguous (how a controller folds "a=1&a=2" into one value depends on the
        // order), and sorting the pairs would make "a=1&a=2" and "a=2&a=1" digest equal. Refuse it.
        Set<String> names = new HashSet<>();
        for (String part : parts) {
            int eq = part.indexOf('=');
            String name = decode(eq < 0 ? part : part.substring(0, eq));
            if (!names.add(name)) {
                throw new IllegalArgumentException(
                        "Query parameter '" + name + "' is repeated; a dual-control approval cannot be bound to it");
            }
        }
        return String.join("&", parts);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * Canonical form of a JSON document (see class comment).
     *
     * @throws IllegalArgumentException when the document is not JSON, repeats an object key, or holds a
     *         number that cannot be represented exactly
     */
    public static String canonicalJson(String json) {
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Body is not valid JSON (or repeats an object key)", e);
        }
        return canonicalJson(tree);
    }

    /** Canonical form of an already-parsed JSON value. */
    public static String canonicalJson(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    /**
     * Reads one JSON value from {@code parser} the way {@link #canonicalJson(String)} does: floats as exact
     * decimals, repeated keys refused. Used to deserialise the {@code targetBody} an approver submits, so the
     * approver's side and the live request's side parse numbers identically.
     */
    public static JsonNode readExact(JsonParser parser) {
        return EMBEDDED_MAPPER.readTree(parser);
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(MAPPER.writeValueAsString(e.getKey())).append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isNumber()) {
            BigDecimal d = node.decimalValue();
            if (Math.abs((long) d.scale()) > MAX_NUMBER_MAGNITUDE || d.precision() > MAX_NUMBER_MAGNITUDE) {
                throw new IllegalArgumentException("Number is too large to bind exactly");
            }
            sb.append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue());
        } else {
            sb.append(MAPPER.writeValueAsString(node.asString()));
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
