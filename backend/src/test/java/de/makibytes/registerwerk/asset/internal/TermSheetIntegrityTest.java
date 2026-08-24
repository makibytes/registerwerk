package de.makibytes.registerwerk.asset.internal;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TermSheetIntegrityTest {

    @Test
    void acceptsContentMatchingOnChainKeccakHash() {
        byte[] content = "binding terms".getBytes(StandardCharsets.UTF_8);
        String hash = Numeric.toHexString(Hash.sha3(content));

        assertThatCode(() -> TermSheetOnChainFetchService.verifyOnChainHash(hash, content))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsContentThatDoesNotMatchOnChainHash() {
        byte[] expectedContent = "original".getBytes(StandardCharsets.UTF_8);
        String hash = Numeric.toHexString(Hash.sha3(expectedContent));

        assertThatThrownBy(() -> TermSheetOnChainFetchService.verifyOnChainHash(
                hash, "modified".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("on-chain content hash");
    }

    @Test
    void rejectsRemoteDocumentsAboveTwentyMegabytes() {
        byte[] oversized = new byte[20 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> TermSheetOnChainFetchService.requireDownloadSize(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20 MB");
    }
}
