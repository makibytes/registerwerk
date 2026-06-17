package de.makibytes.registerwerk.asset.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SSRF guard for on-chain term-sheet fetch")
class SsrfGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/secret",
            "http://localhost/admin",
            "https://192.168.1.1/internal",
            "https://10.0.0.1/metadata",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/secret"
    })
    @DisplayName("rejects loopback, site-local, and link-local addresses")
    void rejectsInternalAddresses(String url) {
        assertThatThrownBy(() -> TermSheetOnChainFetchService.rejectInternalAddress(URI.create(url)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF blocked");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file",
            "file:///etc/passwd",
            "gopher://evil.com/"
    })
    @DisplayName("rejects disallowed schemes")
    void rejectsDisallowedSchemes(String url) {
        assertThatThrownBy(() -> TermSheetOnChainFetchService.rejectInternalAddress(URI.create(url)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF blocked");
    }

    @Test
    @DisplayName("allows HTTPS to public hosts")
    void allowsPublicHttps() {
        assertThatCode(() -> TermSheetOnChainFetchService.rejectInternalAddress(
                URI.create("https://arweave.net/abc123")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("allows HTTP to public hosts")
    void allowsPublicHttp() {
        assertThatCode(() -> TermSheetOnChainFetchService.rejectInternalAddress(
                URI.create("http://ipfs.io/ipfs/Qm123")))
                .doesNotThrowAnyException();
    }
}
