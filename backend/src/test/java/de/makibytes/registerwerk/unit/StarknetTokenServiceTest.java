package de.makibytes.registerwerk.unit;

import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.blockchain.internal.deploy.StarknetTokenService;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StarknetTokenService unit tests")
class StarknetTokenServiceTest {

    @Mock
    private ChainConfigRepository chainConfigRepository;

    @Mock
    private WalletSigner walletSigner;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private StarknetTokenService starknetTokenService;

    // ── STARK ECDSA crypto tests ──────────────────────────────────────────────

    @Test
    @DisplayName("starknetKeccak should produce a value in the felt252 range")
    void starknetKeccak_shouldProduceValueInFelt252Range() {
        BigInteger result = StarknetTokenService.starknetKeccak("invoke");

        assertThat(result.signum()).isGreaterThan(0);
        // Must fit in 250 bits (starknet_keccak truncates the top 6 bits of keccak256)
        BigInteger maxFelt250 = BigInteger.ONE.shiftLeft(250).subtract(BigInteger.ONE);
        assertThat(result).isLessThanOrEqualTo(maxFelt250);
    }

    @Test
    @DisplayName("starknetKeccak of 'deployContract' should match the known UDC selector")
    void starknetKeccak_knownSelectorShouldMatchUdcDeployContract() {
        BigInteger selector = StarknetTokenService.starknetKeccak("deployContract");

        // starknet_keccak("deployContract") = keccak256("deployContract") & ((1<<250)-1)
        BigInteger expected = new BigInteger(
                "1987cbd17808b9a23693d4de7e246a443cfe37e6e7fbaeabd7d7e6532b07c3d", 16);
        assertThat(selector).isEqualTo(expected);
    }

    @Test
    @DisplayName("EC point multiplication should produce a point on the STARK curve")
    void ecMul_resultShouldBeOnStarkCurve() {
        BigInteger privKey = new BigInteger("123456789");
        BigInteger gx = new BigInteger(
                "1ef15c18599971b7beced415a40f0c7deacfd9b0d1819e03d723d8bc943cfca", 16);
        BigInteger gy = new BigInteger(
                "5668060aa49730b7be4801df46ec62de53ecd11abe43a32873000c36e8dc1f", 16);

        BigInteger[] pubKey = StarknetTokenService.ecMul(privKey, new BigInteger[]{gx, gy});

        assertThat(pubKey).isNotNull();
        assertThat(pubKey).hasSize(2);
        // Verify the point lies on y^2 = x^3 + x + beta (mod P) — the STARK Weierstrass curve
        BigInteger P = new BigInteger(
                "800000000000011000000000000000000000000000000000000000000000001", 16);
        BigInteger BETA = new BigInteger(
                "6f21413efbe40de150e596d72f7a8c5609ad26c15c915c1f4cdfcb99cee9e89", 16);
        BigInteger lhs = pubKey[1].pow(2).mod(P);
        BigInteger rhs = pubKey[0].pow(3).add(pubKey[0]).add(BETA).mod(P);
        assertThat(lhs).isEqualTo(rhs);
    }

    @Test
    @DisplayName("STARK sign and verify: signature r and s should be in field range")
    void starkSign_shouldProduceValidSignatureComponents() {
        BigInteger N = new BigInteger(
                "800000000000010ffffffffffffffffb781126dcae7b2321e66a241adc64d2f", 16);
        BigInteger privKey = new BigInteger("deadbeefcafe1234", 16);
        BigInteger msgHash = new BigInteger("abcdef1234567890", 16);

        BigInteger[] sig = StarknetTokenService.starkSign(privKey, msgHash);

        assertThat(sig).hasSize(2);
        BigInteger r = sig[0], s = sig[1];
        assertThat(r.signum()).isGreaterThan(0);
        assertThat(s.signum()).isGreaterThan(0);
        assertThat(r).isLessThan(N);
        assertThat(s).isLessThan(N);
    }

    @Test
    @DisplayName("STARK sign should be deterministic (same key and hash → same signature)")
    void starkSign_shouldBeDeterministic() {
        BigInteger privKey = new BigInteger("deadbeefcafe1234", 16);
        BigInteger msgHash = new BigInteger("abcdef1234567890", 16);

        BigInteger[] sig1 = StarknetTokenService.starkSign(privKey, msgHash);
        BigInteger[] sig2 = StarknetTokenService.starkSign(privKey, msgHash);

        assertThat(sig1[0]).isEqualTo(sig2[0]);
        assertThat(sig1[1]).isEqualTo(sig2[1]);
    }

    @Test
    @DisplayName("parseHexFelt should handle 0x-prefixed and plain hex strings")
    void parseHexFelt_shouldHandleBothPrefixStyles() {
        BigInteger withPrefix = StarknetTokenService.parseHexFelt(
                "0x049ee3eba8c1600700ee1b87eb599f16716b0b1022947733551fde4050ca6804");
        BigInteger withoutPrefix = StarknetTokenService.parseHexFelt(
                "049ee3eba8c1600700ee1b87eb599f16716b0b1022947733551fde4050ca6804");

        assertThat(withPrefix).isEqualTo(withoutPrefix);
        assertThat(withPrefix.signum()).isGreaterThan(0);
    }

    @Test
    @DisplayName("ecAdd of a point with itself should equal ecDouble")
    void ecAdd_selfEqualsEcDouble() {
        BigInteger gx = new BigInteger(
                "1ef15c18599971b7beced415a40f0c7deacfd9b0d1819e03d723d8bc943cfca", 16);
        BigInteger gy = new BigInteger(
                "5668060aa49730b7be4801df46ec62de53ecd11abe43a32873000c36e8dc1f", 16);
        BigInteger[] G = {gx, gy};

        BigInteger[] doubled  = StarknetTokenService.ecDouble(G);
        BigInteger[] added    = StarknetTokenService.ecAdd(G, G);

        assertThat(doubled[0]).isEqualTo(added[0]);
        assertThat(doubled[1]).isEqualTo(added[1]);
    }

    // ── Service routing tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("createCairoErc20 should fail when no Starknet chain config is enabled")
    void createCairoErc20_shouldFailWhenNoChainConfigured() {
        when(chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET))
                .thenReturn(List.of());

        assertThatThrownBy(() -> starknetTokenService.createCairoErc20(
                UUID.randomUUID(), Network.TESTNET, "0x0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No enabled Starknet chain config");
    }

    @Test
    @DisplayName("createCairoErc20 should fail when no TESTNET chain config exists")
    void createCairoErc20_shouldFilterByNetworkType() {
        ChainConfig mainnetConfig = new ChainConfig();
        mainnetConfig.setNetworkType(ChainConfig.NetworkType.MAINNET);
        mainnetConfig.setRpcUrl("https://starknet-mainnet.example.com");

        when(chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STARKNET))
                .thenReturn(List.of(mainnetConfig));

        assertThatThrownBy(() -> starknetTokenService.createCairoErc20(
                UUID.randomUUID(), Network.TESTNET, "0x0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No enabled Starknet chain config for TESTNET");
    }
}
