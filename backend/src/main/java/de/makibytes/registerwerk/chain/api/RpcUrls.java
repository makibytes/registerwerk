package de.makibytes.registerwerk.chain.api;

import java.net.URI;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Helpers for RPC node URLs that may carry secrets (API keys in the path or query). */
public final class RpcUrls {

    private static final Pattern KEY_LIKE_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{20,}$");

    private RpcUrls() {}

    /** Removes userinfo and query values and masks key-like path segments, for audit events and logs. */
    public static String redact(String rawUrl) {
        if (rawUrl == null) return null;
        try {
            URI uri = URI.create(rawUrl.trim());
            StringBuilder sb = new StringBuilder();
            if (uri.getScheme() != null) sb.append(uri.getScheme()).append("://");
            if (uri.getHost() != null) sb.append(uri.getHost());
            if (uri.getPort() > 0) sb.append(':').append(uri.getPort());
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty()) {
                StringBuilder p = new StringBuilder();
                for (String segment : path.split("/", -1)) {
                    if (!p.isEmpty() || !segment.isEmpty()) p.append('/');
                    p.append(KEY_LIKE_SEGMENT.matcher(segment).matches() ? "***" : segment);
                }
                sb.append(p.toString().replaceAll("^/+", "/"));
            }
            String query = uri.getRawQuery();
            if (query != null && !query.isEmpty()) {
                sb.append('?').append(Arrays.stream(query.split("&"))
                        .map(kv -> kv.contains("=") ? kv.substring(0, kv.indexOf('=')) + "=***" : kv)
                        .reduce((x, y) -> x + "&" + y).orElse(""));
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "<unparseable url>";
        }
    }
}
