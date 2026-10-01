package de.makibytes.registerwerk.stepup.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DualControlTarget - what an approval is bound to (K3, 6-08)")
class DualControlTargetTest {

    @Test
    @DisplayName("method, path and query change the digest; trailing slash, method case and query order do not")
    void digestBindsTheRequest() {
        String base = DualControlTarget.digest("POST", "/api/v1/x/1", "", null);
        assertThat(DualControlTarget.digest("POST", "/api/v1/x/2", "", null)).isNotEqualTo(base);
        assertThat(DualControlTarget.digest("PUT", "/api/v1/x/1", "", null)).isNotEqualTo(base);
        assertThat(DualControlTarget.digest("POST", "/api/v1/x/1", "force=true", null)).isNotEqualTo(base);
        assertThat(DualControlTarget.digest("post", "/api/v1/x/1/", "", null)).isEqualTo(base);
        assertThat(DualControlTarget.digest("POST", "/api/v1/x/1", "b=2&a=1", null))
                .isEqualTo(DualControlTarget.digest("POST", "/api/v1/x/1", "a=1&b=2", null));
    }

    @Test
    @DisplayName("path case is preserved (Solana addresses are case sensitive)")
    void pathCaseSensitive() {
        assertThat(DualControlTarget.digest("POST", "/t/AbC", "", null))
                .isNotEqualTo(DualControlTarget.digest("POST", "/t/abc", "", null));
    }

    @Test
    @DisplayName("a client's 'METHOD /path?query' target string yields the same digest as the server's parts")
    void targetStringMatchesParts() {
        assertThat(DualControlTarget.digestOfTarget("POST /api/v1/x/1?b=2&a=1", null))
                .isEqualTo(DualControlTarget.digest("POST", "/api/v1/x/1", "a=1&b=2", null));
        assertThatThrownBy(() -> DualControlTarget.digestOfTarget("/api/v1/x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DualControlTarget.digestOfTarget("POST api/v1/x", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("canonical JSON ignores key order, whitespace and numeric formatting but not values")
    void canonicalJson() {
        String a = DualControlTarget.canonicalJson("{ \"to\": \"0xAbC\", \"amount\": 100.50, \"n\": [1, {\"b\":2,\"a\":1}] }");
        String b = DualControlTarget.canonicalJson("{\"n\":[1,{\"a\":1,\"b\":2}],\"amount\":100.5,\"to\":\"0xAbC\"}");
        assertThat(a).isEqualTo(b).isEqualTo("{\"amount\":100.5,\"n\":[1,{\"a\":1,\"b\":2}],\"to\":\"0xAbC\"}");
        assertThat(DualControlTarget.canonicalJson("{\"to\":\"0xabc\",\"amount\":100.5}")).isNotEqualTo(a);
    }

    @Test
    @DisplayName("a body-bound digest differs when amount or destination differ")
    void bodyChangesDigest() {
        String one = DualControlTarget.digest("POST", "/burn", "", DualControlTarget.canonicalJson("{\"amount\":5}"));
        String two = DualControlTarget.digest("POST", "/burn", "", DualControlTarget.canonicalJson("{\"amount\":500}"));
        assertThat(one).isNotEqualTo(two).isNotEqualTo(DualControlTarget.digest("POST", "/burn", "", null));
    }

    @Test
    @DisplayName("invalid JSON is rejected")
    void invalidJson() {
        assertThatThrownBy(() -> DualControlTarget.canonicalJson("{not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
