package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcUrls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Trust rules for RPC node URLs (P4C-6 interim / parked T4-08): https/wss only, unless the host is
 * loopback or private and {@code registerwerk.rpc.allow-insecure-private=true}; optionally restricted
 * to the chain's {@code rpc_allowed_hosts}. Also redacts URLs for audit events and logs. Applied when a
 * node is added or its URL changes; existing rows are not rewritten.
 */
@Component
public class RpcNodeUrlPolicy {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final boolean allowInsecurePrivate;

    public RpcNodeUrlPolicy(
            @Value("${registerwerk.rpc.allow-insecure-private:false}") boolean allowInsecurePrivate) {
        this.allowInsecurePrivate = allowInsecurePrivate;
    }

    /** @throws IllegalArgumentException (HTTP 400) when the URL violates the policy. */
    public void validate(ChainConfig chain, String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("RPC node URL is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("RPC node URL must contain a host");
        }
        boolean secure = scheme.equals("https") || scheme.equals("wss");
        boolean plain = scheme.equals("http") || scheme.equals("ws");
        if (!secure) {
            if (!plain) {
                throw new IllegalArgumentException("RPC node URL scheme must be https or wss");
            }
            if (!(allowInsecurePrivate && isLoopbackOrPrivate(host))) {
                throw new IllegalArgumentException("RPC node URL must use https/wss (plain http/ws is only "
                        + "accepted for loopback/private hosts when registerwerk.rpc.allow-insecure-private=true)");
            }
        }
        String allowed = chain.getRpcAllowedHosts();
        if (allowed != null && !allowed.isBlank()
                && Arrays.stream(allowed.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                        .noneMatch(entry -> hostMatches(entry.toLowerCase(Locale.ROOT), host))) {
            throw new IllegalArgumentException("RPC node host '" + host + "' is not in the allowed hosts of chain "
                    + chain.getIdentifier());
        }
    }

    private static boolean hostMatches(String entry, String host) {
        if (entry.startsWith("*.")) {
            return host.endsWith(entry.substring(1));
        }
        return entry.equals(host);
    }

    static boolean isLoopbackOrPrivate(String host) {
        if (host.equals("localhost") || host.equals("::1") || host.equals("[::1]")
                || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        var m = IPV4.matcher(host);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        }
        // Unqualified single-label names are compose/cluster service names (e.g. "anvil").
        return !host.contains(".") && !host.contains(":");
    }

    /** @see RpcUrls#redact */
    public static String redact(String rawUrl) {
        return RpcUrls.redact(rawUrl);
    }
}
