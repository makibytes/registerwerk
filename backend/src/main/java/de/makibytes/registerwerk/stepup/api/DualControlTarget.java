package de.makibytes.registerwerk.stepup.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 */
public final class DualControlTarget {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
        return String.join("&", parts);
    }

    /** Canonical form of a JSON document (see class comment). */
    public static String canonicalJson(String json) {
        try {
            StringBuilder sb = new StringBuilder();
            write(MAPPER.readTree(json), sb);
            return sb.toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Body is not valid JSON", e);
        }
    }

    /** Canonical form of an already-parsed JSON value. */
    public static String canonicalJson(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
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
