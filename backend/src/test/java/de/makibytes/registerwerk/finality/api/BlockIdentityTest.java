package de.makibytes.registerwerk.finality.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockIdentityTest {

    @Test
    void normalizesOnlyPrefixedHexIdentities() {
        assertThat(BlockIdentity.normalize("0xAbC123")).isEqualTo("0xabc123");
        assertThat(BlockIdentity.normalize("0XAbC123")).isEqualTo("0xabc123");
        assertThat(BlockIdentity.normalize("SoLanaBase58A")).isEqualTo("SoLanaBase58A");
        assertThat(BlockIdentity.normalize("0xnot-hex")).isEqualTo("0xnot-hex");
        assertThat(BlockIdentity.normalize(null)).isNull();
    }

    @Test
    void comparesHexCaseInsensitivelyButOtherProtocolsExactly() {
        assertThat(BlockIdentity.sameHash("0xAbC", "0xabc")).isTrue();
        assertThat(BlockIdentity.sameHash("SoLanaA", "SoLanaa")).isFalse();
        assertThat(BlockIdentity.sameHash(null, null)).isTrue();
    }

    @Test
    void incarnationRequiresBothHeightAndNonNullMatchingHash() {
        assertThat(BlockIdentity.sameIncarnation(42L, "0xAbC", 42L, "0xabc")).isTrue();
        assertThat(BlockIdentity.sameIncarnation(42L, "SolanaA", 42L, "Solanaa")).isFalse();
        assertThat(BlockIdentity.sameIncarnation(43L, "0xabc", 42L, "0xabc")).isFalse();
        assertThat(BlockIdentity.sameIncarnation(42L, null, 42L, "0xabc")).isFalse();
    }
}
