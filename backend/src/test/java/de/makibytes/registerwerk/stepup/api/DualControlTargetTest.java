package de.makibytes.registerwerk.stepup.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DualControlTarget - what an approval is bound to (K3, 6-08; exact numbers and ambiguity C2)")
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


    @Test
    @DisplayName("object keys are sorted and whitespace is dropped")
    void sortsKeys() {
        assertThat(DualControlTarget.canonicalJson("{ \"b\": 1, \"a\": [true, null, \"x\"] }"))
                .isEqualTo("{\"a\":[true,null,\"x\"],\"b\":1}");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "1.50|1.5",
            "100|100",
            "1e3|1000",
            "1E+3|1000",
            "0.000|0",
            "-0|0",
            "-0.0|0",
            "1E-7|0.0000001",
            "12345678901234567890.123456789012345678|12345678901234567890.123456789012345678",
            "123456789012345678901234567890|123456789012345678901234567890",
            "0.1000000000000000055511151231257827|0.1000000000000000055511151231257827",
    })
    @DisplayName("numbers are exact BigDecimals in plain form without trailing zeros (never a double)")
    void numbersAreExact(String token, String expected) {
        assertThat(DualControlTarget.canonicalJson("{\"v\":" + token + "}")).isEqualTo("{\"v\":" + expected + "}");
    }

    @Test
    @DisplayName("a string that looks like a number is not normalised")
    void stringsAreVerbatim() {
        assertThat(DualControlTarget.canonicalJson("{\"v\":\"1.50\"}")).isEqualTo("{\"v\":\"1.50\"}");
    }

    @Test
    @DisplayName("two amounts that differ only beyond double precision give different digests")
    void digestSeesBeyondDoublePrecision() {
        String a = DualControlTarget.canonicalJson("{\"amount\":1000000000000000.000000000000000001}");
        String b = DualControlTarget.canonicalJson("{\"amount\":1000000000000000.000000000000000002}");
        assertThat(a).isNotEqualTo(b);
        assertThat(DualControlTarget.digest("POST", "/x", "", a)).isNotEqualTo(DualControlTarget.digest("POST", "/x", "", b));
    }

    @Test
    @DisplayName("a huge exponent is refused instead of expanding into gigabytes of zeros")
    void hugeExponentIsRefused() {
        assertThatThrownBy(() -> DualControlTarget.canonicalJson("{\"v\":1e999999999}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("duplicate object keys are refused, at any depth - last-wins/first-wins parsers must never disagree")
    void duplicateKeysAreRefused() {
        assertThatThrownBy(() -> DualControlTarget.canonicalJson("{\"amount\":\"1\",\"amount\":\"999\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DualControlTarget.canonicalJson("{\"outer\":{\"to\":\"0xA\",\"to\":\"0xB\"}}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DualControlTarget.canonicalJson("[{\"k\":1,\"k\":1}]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a repeated query parameter is refused: its order would change what the controller binds")
    void duplicateQueryParametersAreRefused() {
        assertThatThrownBy(() -> DualControlTarget.digest("POST", "/api/v1/x", "a=1&a=2", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DualControlTarget.digestOfTarget("POST /api/v1/x?a=1&b=2&a=1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DualControlTarget.digest("POST", "/api/v1/x", "a&a=", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("distinct query parameters are order-insensitive")
    void distinctQueryParametersAreSorted() {
        assertThat(DualControlTarget.digest("POST", "/api/v1/x", "b=2&a=1", null))
                .isEqualTo(DualControlTarget.digest("post", "/api/v1/x/", "a=1&b=2", null));
    }

    @Test
    @DisplayName("an empty canonical body differs from 'not body-bound'")
    void emptyBodyIsBound() {
        assertThat(DualControlTarget.digest("POST", "/x", "", ""))
                .isNotEqualTo(DualControlTarget.digest("POST", "/x", "", null));
    }

    @Test
    @DisplayName("string escaping is fixed: short escapes for \\b \\t \\n \\f \\r, upper-case \\u00XX for other control characters, nothing else escaped")
    void stringEscaping() {
        String json = "{\"k\":\"a\\u0001\\u001f\\b\\t\\n\\f\\r\\\"\\\\/\\u00e4\\u20ac\\u007f\\u2028\"}";
        assertThat(DualControlTarget.canonicalJson(json))
                .isEqualTo("{\"k\":\"a\\u0001\\u001F\\b\\t\\n\\f\\r\\\"\\\\/\u00e4\u20ac\u007f\u2028\"}");
        // keys are escaped the same way
        assertThat(DualControlTarget.canonicalJson("{\"\\u001f\":1}")).isEqualTo("{\"\\u001F\":1}");
    }
}
