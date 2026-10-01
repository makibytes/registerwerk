package de.makibytes.registerwerk.webhook.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutboundUrlPolicy — webhook SSRF matrix (5D-08)")
class OutboundUrlPolicyTest {

    private static OutboundUrlPolicy strict() {
        return new OutboundUrlPolicy(new WebhookProperties(), false, literalResolver());
    }

    private static OutboundUrlPolicy lenient() {
        WebhookProperties p = new WebhookProperties();
        p.setAllowInsecureUrls(true);
        return new OutboundUrlPolicy(p, false, literalResolver());
    }

    /** IP literals resolve without touching DNS; anything else is not resolvable. */
    private static Function<String, InetAddress[]> literalResolver() {
        return host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @ParameterizedTest(name = "blocked: {0}")
    @ValueSource(strings = {
            "https://127.0.0.1/hook", "https://127.255.255.254/hook", "https://10.0.0.5/hook",
            "https://172.16.0.1/hook", "https://172.31.255.255/hook", "https://192.168.1.10/hook",
            "https://169.254.169.254/latest/meta-data", "https://100.64.0.1/hook", "https://100.127.255.255/hook",
            "https://0.0.0.0/hook", "https://0.1.2.3/hook", "https://224.0.0.1/hook", "https://255.255.255.255/hook",
            "https://198.18.0.1/hook", "https://192.0.2.7/hook", "https://198.51.100.9/hook", "https://203.0.113.9/hook",
            "https://[::1]/hook", "https://[::]/hook", "https://[fc00::1]/hook", "https://[fd12:3456::1]/hook",
            "https://[fd00:ec2::254]/hook", "https://[fe80::1]/hook", "https://[fec0::1]/hook", "https://[ff02::1]/hook",
            "https://[::ffff:127.0.0.1]/hook", "https://[::ffff:169.254.169.254]/hook", "https://[::ffff:10.0.0.1]/hook",
            "https://[::ffff:100.64.0.1]/hook", "https://[64:ff9b::7f00:1]/hook", "https://[2002:7f00:1::]/hook",
            "https://[2001:db8::1]/hook", "https://[2001::1]/hook",
            // decimal-integer encoding resolves to loopback and is caught after resolution
            "https://2130706433/hook"
    })
    void blocksNonPublicAddresses(String url) {
        assertThat(strict().check(url)).isEqualTo(OutboundUrlPolicy.Verdict.BLOCKED);
        assertThatThrownBy(() -> strict().requireAllowed(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "allowed: {0}")
    @ValueSource(strings = {
            "https://93.184.216.34/hook", "https://8.8.8.8:8443/hook", "https://100.63.255.255/hook",
            "https://100.128.0.1/hook", "https://172.32.0.1/hook", "https://[2606:4700:4700::1111]/hook"
    })
    void allowsPublicAddresses(String url) {
        assertThat(strict().check(url)).isEqualTo(OutboundUrlPolicy.Verdict.OK);
    }

    @ParameterizedTest(name = "blocked shape: {0}")
    @ValueSource(strings = {
            "http://93.184.216.34/hook",                // http
            "ftp://93.184.216.34/hook",
            "https://93.184.216.34:8080/hook",          // port not on the allow-list
            "https://user:pw@93.184.216.34/hook",       // userinfo
            "https:///hook", "not a url", ""
    })
    void blocksWrongSchemePortUserinfoAndGarbage(String url) {
        assertThat(strict().check(url)).isEqualTo(OutboundUrlPolicy.Verdict.BLOCKED);
    }

    @Test
    @DisplayName("every resolved record must be public: one private A/AAAA blocks the whole name")
    void mixedAnswerIsBlocked() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new WebhookProperties(), false, host -> {
            try {
                return new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")};
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(policy.check("https://hook.example.com/x")).isEqualTo(OutboundUrlPolicy.Verdict.BLOCKED);
    }

    @Test
    @DisplayName("an unresolvable name is UNRESOLVABLE, not BLOCKED")
    void unresolvable() {
        assertThat(strict().check("https://no-such-host.invalid/x")).isEqualTo(OutboundUrlPolicy.Verdict.UNRESOLVABLE);
    }

    @Test
    @DisplayName("DNS rebinding: a name that is public at check time and private at connect time is refused at connect")
    void rebindingIsRefusedAtConnectTime() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new WebhookProperties(), false, host -> {
            try {
                return new InetAddress[]{InetAddress.getByName(lookups.getAndIncrement() == 0 ? "93.184.216.34" : "10.0.0.5")};
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(policy.check("https://rebind.example.com/x")).isEqualTo(OutboundUrlPolicy.Verdict.OK);
        assertThatThrownBy(() -> policy.resolveValidated("rebind.example.com"))
                .isInstanceOf(OutboundUrlPolicy.BlockedDestinationException.class);
    }

    @Test
    @DisplayName("allow-insecure-urls: http, any port and loopback/private are allowed, metadata and link-local never")
    void lenientMode() {
        assertThat(lenient().check("http://127.0.0.1:9000/hook")).isEqualTo(OutboundUrlPolicy.Verdict.OK);
        assertThat(lenient().check("http://10.1.2.3:8080/hook")).isEqualTo(OutboundUrlPolicy.Verdict.OK);
        assertThat(lenient().check("http://[::1]:9000/hook")).isEqualTo(OutboundUrlPolicy.Verdict.OK);
        assertThat(lenient().check("http://169.254.169.254/latest")).isEqualTo(OutboundUrlPolicy.Verdict.BLOCKED);
        assertThat(lenient().check("http://[fd00:ec2::254]/latest")).isEqualTo(OutboundUrlPolicy.Verdict.BLOCKED);
        assertThat(lenient().check("http://[::ffff:169.254.169.254]/latest")).isEqualTo(OutboundUrlPolicy.Verdict.BLOCKED);
        assertThat(lenient().check("http://0.0.0.0/hook")).isEqualTo(OutboundUrlPolicy.Verdict.BLOCKED);
        assertThat(lenient().check("http://224.0.0.1/hook")).isEqualTo(OutboundUrlPolicy.Verdict.BLOCKED);
    }

    @Test
    @DisplayName("allow-insecure-urls is refused when REGISTERWERK_PRODUCTION_MODE is on")
    void insecureRefusedInProduction() {
        WebhookProperties p = new WebhookProperties();
        p.setAllowInsecureUrls(true);
        assertThatThrownBy(() -> new OutboundUrlPolicy(p, true, literalResolver()))
                .isInstanceOf(IllegalStateException.class);
    }
}
