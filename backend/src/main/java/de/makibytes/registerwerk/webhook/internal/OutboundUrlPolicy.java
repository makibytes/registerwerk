package de.makibytes.registerwerk.webhook.internal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.Function;

/**
 * SSRF policy for customer-supplied webhook targets (5D-08). It replaces reliance on the incomplete
 * pre-connect check in {@code TermSheetOnChainFetchService.rejectInternalAddress} (which misses CGNAT,
 * IPv6 ULA, IPv4-mapped IPv6 and friends and validates only before connecting).
 *
 * <p>Two layers use this class: {@link #check(String)} validates the URL at create/update and
 * before every send, and {@link #resolveValidated(String)} is what the HTTP client's
 * {@code DnsResolver} calls at <em>connect</em> time and returns — the connection is opened to
 * exactly the addresses that were validated, so a DNS answer that changes between "check" and
 * "connect" (rebinding) cannot redirect the request. Redirects are not followed by the sender.
 *
 * <p>Default: https only, no userinfo, ports 443/8443, every resolved A/AAAA record must be a public
 * address. With {@code registerwerk.webhook.allow-insecure-urls=true} (refused when
 * {@code REGISTERWERK_PRODUCTION_MODE=true}) http, any port and loopback/private targets are allowed for
 * local receivers; link-local (cloud metadata), unspecified and multicast targets stay blocked.
 */
@Component
public class OutboundUrlPolicy {

    /** The destination is not permitted; the message never echoes resolver details to the caller. */
    public static class BlockedDestinationException extends UnknownHostException {
        BlockedDestinationException(String message) { super(message); }
    }

    public enum Verdict { OK, BLOCKED, UNRESOLVABLE }

    private final WebhookProperties properties;
    private final Function<String, InetAddress[]> resolver;

    @Autowired
    OutboundUrlPolicy(WebhookProperties properties,
                      org.springframework.core.env.Environment environment) {
        this(properties, de.makibytes.registerwerk.shared.ProductionMode.resolve(environment), host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    OutboundUrlPolicy(WebhookProperties properties, boolean productionMode,
                      Function<String, InetAddress[]> resolver) {
        if (properties.isAllowInsecureUrls() && productionMode) {
            throw new IllegalStateException(
                    "registerwerk.webhook.allow-insecure-urls=true is not permitted when REGISTERWERK_PRODUCTION_MODE=true");
        }
        this.properties = properties;
        this.resolver = resolver;
    }

    /** Validates scheme, userinfo, port and — after DNS resolution — every resolved address. */
    public Verdict check(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            return Verdict.BLOCKED;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean insecure = properties.isAllowInsecureUrls();
        if (!(scheme.equals("https") || (insecure && scheme.equals("http")))) {
            return Verdict.BLOCKED;
        }
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            return Verdict.BLOCKED;
        }
        int port = uri.getPort() != -1 ? uri.getPort() : (scheme.equals("https") ? 443 : 80);
        if (!insecure && !properties.getAllowedPorts().contains(port)) {
            return Verdict.BLOCKED;
        }
        try {
            resolveValidated(stripBrackets(uri.getHost()));
            return Verdict.OK;
        } catch (BlockedDestinationException e) {
            return Verdict.BLOCKED;
        } catch (UnknownHostException e) {
            return Verdict.UNRESOLVABLE;
        }
    }

    /** Like {@link #check} but throws an {@link IllegalArgumentException} (HTTP 400) with a generic message. */
    public void requireAllowed(String url) {
        if (check(url) != Verdict.OK) {
            throw new IllegalArgumentException(
                    "Webhook URL is not permitted: it must be a public https endpoint on port 443 or 8443");
        }
    }

    /**
     * Resolves {@code host} and returns the addresses only if <em>all</em> of them are permitted. This is
     * the single resolution the HTTP connection uses (address pinning).
     */
    public InetAddress[] resolveValidated(String host) throws UnknownHostException {
        InetAddress[] addresses;
        try {
            addresses = resolver.apply(host);
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof UnknownHostException uhe) throw uhe;
            throw e;
        }
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address, properties.isAllowInsecureUrls())) {
                throw new BlockedDestinationException("Destination address not permitted");
            }
        }
        return addresses;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    // ── address classification ───────────────────────────────────────────────────────────────────

    /**
     * @param lenient dev mode: loopback and private/ULA/CGNAT space is allowed, but link-local
     *                (incl. 169.254.169.254 and {@code fd00:ec2::254}), unspecified, multicast and
     *                reserved ranges are not.
     */
    static boolean isBlocked(InetAddress address, boolean lenient) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address || b.length == 4) {
            return isBlockedV4(b, lenient);
        }
        if (!(address instanceof Inet6Address)) {
            return true;
        }
        // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d): judge the embedded IPv4
        if (allZero(b, 0, 10) && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
            if (allZero(b, 0, 12) && allZero(b, 12, 16)) return true;                    // ::
            if (allZero(b, 0, 15) && b[15] == 1) return !lenient;                         // ::1
            return isBlockedV4(new byte[]{b[12], b[13], b[14], b[15]}, lenient);
        }
        // NAT64 well-known prefix 64:ff9b::/96 embeds an IPv4 in the last 32 bits
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12)) {
            return isBlockedV4(new byte[]{b[12], b[13], b[14], b[15]}, lenient);
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        if (b0 == 0xff) return true;                                                       // multicast ff00::/8
        if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return true;                               // link-local fe80::/10
        if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) return !lenient;                           // site-local fec0::/10
        if ((b0 & 0xfe) == 0xfc) {                                                         // ULA fc00::/7
            // AWS IMDS over IPv6 (fd00:ec2::254) is never reachable, even in lenient mode
            if (b0 == 0xfd && b1 == 0x00 && b[2] == 0x0e && (b[3] & 0xff) == 0xc2) return true;
            return !lenient;
        }
        if (allZero(b, 0, 15) && b[15] == 1) return !lenient;                              // ::1
        if (allZero(b, 0, 16)) return true;                                                // ::
        if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0) return true;              // Teredo 2001::/32
        if (b0 == 0x20 && b1 == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) return true; // documentation 2001:db8::/32
        if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && (b[3] & 0xf0) == 0x10) return true;  // ORCHID 2001:10::/28
        if (b0 == 0x20 && b1 == 0x02) return true;                                        // 6to4 2002::/16
        if (b0 == 0x01 && b1 == 0x00 && allZero(b, 2, 8)) return true;                     // discard 100::/64
        // Only global unicast 2000::/3 is considered public
        return (b0 & 0xe0) != 0x20;
    }

    private static boolean isBlockedV4(byte[] b, boolean lenient) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        int d = b[2] & 0xff;
        if (a == 0) return true;                                                           // 0.0.0.0/8
        if (a >= 224) return true;                                                         // multicast + reserved + broadcast
        if (a == 169 && c == 254) return true;                                             // link-local / metadata
        boolean nonPublic = a == 10
                || a == 127
                || (a == 100 && (c & 0xc0) == 64)                                          // CGNAT 100.64/10
                || (a == 172 && (c & 0xf0) == 16)
                || (a == 192 && c == 168);
        if (nonPublic) return !lenient;
        if (a == 192 && c == 0 && (d == 0 || d == 2)) return true;                         // 192.0.0/24, 192.0.2/24
        if (a == 192 && c == 88 && d == 99) return true;                                   // 6to4 relay
        if (a == 198 && (c == 18 || c == 19)) return true;                                 // benchmarking 198.18/15
        if (a == 198 && c == 51 && d == 100) return true;                                  // 198.51.100/24
        if (a == 203 && c == 0 && d == 113) return true;                                   // 203.0.113/24
        return false;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }
}
