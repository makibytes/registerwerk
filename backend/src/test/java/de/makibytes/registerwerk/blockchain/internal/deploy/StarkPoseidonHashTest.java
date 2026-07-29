package de.makibytes.registerwerk.blockchain.internal.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Poseidon/Hades implementation against independent test vectors from
 * starknet-rs {@code starknet-crypto/src/poseidon_hash.rs} (generated from cairo-lang
 * v0.11.0). A wrong constant, S-box, MDS row, or padding rule fails all of these.
 */
@DisplayName("StarkPoseidonHash unit tests")
class StarkPoseidonHashTest {

    private static BigInteger felt(String hex) {
        return new BigInteger(hex.replaceFirst("^0x", ""), 16);
    }

    @Test
    @DisplayName("poseidon_hash(x, y) matches the starknet-rs vectors")
    void hashTwoElements() {
        assertThat(StarkPoseidonHash.hash(
                felt("0xb662f9017fa7956fd70e26129b1833e10ad000fd37b4d9f4e0ce6884b7bbe"),
                felt("0x1fe356bf76102cdae1bfbdc173602ead228b12904c00dad9cf16e035468bea")))
                .isEqualTo(felt("0x75540825a6ecc5dc7d7c2f5f868164182742227f1367d66c43ee51ec7937a81"));

        assertThat(StarkPoseidonHash.hash(
                felt("0xf4e01b2032298f86b539e3d3ac05ced20d2ef275273f9325f8827717156529"),
                felt("0x587bc46f5f58e0511b93c31134652a689d761a9e7f234f0f130c52e4679f3a")))
                .isEqualTo(felt("0xbdb3180fdcfd6d6f172beb401af54dd71b6569e6061767234db2b777adf98b"));
    }

    @Test
    @DisplayName("poseidon_hash_single(x) matches the starknet-rs vectors")
    void hashSingleElement() {
        assertThat(StarkPoseidonHash.hashSingle(
                felt("0x9dad5d6f502ccbcb6d34ede04f0337df3b98936aaf782f4cc07d147e3a4fd6")))
                .isEqualTo(felt("0x11222854783f17f1c580ff64671bc3868de034c236f956216e8ed4ab7533455"));

        assertThat(StarkPoseidonHash.hashSingle(
                felt("0x3164a8e2181ff7b83391b4a86bc8967f145c38f10f35fc74e9359a0c78f7b6")))
                .isEqualTo(felt("0x79ad7aa7b98d47705446fa01865942119026ac748d67a5840f06948bce2306b"));
    }

    @Test
    @DisplayName("poseidon_hash_many matches the starknet-rs vectors (odd and even lengths)")
    void hashMany() {
        assertThat(StarkPoseidonHash.hashMany(List.of(
                felt("0x9bf52404586087391c5fbb42538692e7ca2149bac13c145ae4230a51a6fc47"),
                felt("0x40304159ee9d2d611120fbd7c7fb8020cc8f7a599bfa108e0e085222b862c0"),
                felt("0x46286e4f3c450761d960d6a151a9c0988f9e16f8a48d4c0a85817c009f806a"))))
                .isEqualTo(felt("0x1ec38b38dc88bac7b0ed6ff6326f975a06a59ac601b417745fd412a5d38e4f7"));

        assertThat(StarkPoseidonHash.hashMany(List.of(
                felt("0xbdace8883922662601b2fd197bb660b081fcf383ede60725bd080d4b5f2fd3"),
                felt("0x1eb1daaf3fdad326b959dec70ced23649cdf8786537cee0c5758a1a4229097"),
                felt("0x869ca04071b779d6f940cdf33e62d51521e19223ab148ef571856ff3a44ff1"),
                felt("0x533e6df8d7c4b634b1f27035c8676a7439c635e1fea356484de7f0de677930"))))
                .isEqualTo(felt("0x2520b8f910174c3e650725baacad4efafaae7623c69a0b5513d75e500f36624"));
    }
}
