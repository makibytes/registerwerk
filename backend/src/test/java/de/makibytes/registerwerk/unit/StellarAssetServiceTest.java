package de.makibytes.registerwerk.unit;

import tools.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.blockchain.internal.deploy.StellarAssetService;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StellarAssetService unit tests")
class StellarAssetServiceTest {

    @Mock
    private ChainConfigRepository chainConfigRepository;

    @Mock
    private WalletSigner walletSigner;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private StellarAssetService stellarAssetService;

    // ── StrKey encode/decode tests ────────────────────────────────────────────

    @Test
    @DisplayName("strKeyEncode then strKeyDecode should round-trip the public key bytes")
    void strKeyEncodeAndDecode_shouldRoundTrip() {
        byte[] publicKey = new byte[32];
        new SecureRandom().nextBytes(publicKey);

        String gAddress = StellarAssetService.strKeyEncode(publicKey);
        byte[] decoded = StellarAssetService.strKeyDecode(gAddress);

        assertThat(decoded).isEqualTo(publicKey);
    }

    @Test
    @DisplayName("strKeyEncode should always produce a 56-character G-address")
    void strKeyEncode_shouldProduce56CharAddress() {
        byte[] publicKey = new byte[32];
        new SecureRandom().nextBytes(publicKey);

        String gAddress = StellarAssetService.strKeyEncode(publicKey);

        assertThat(gAddress).hasSize(56);
        assertThat(gAddress.charAt(0)).isEqualTo('G');
    }

    @Test
    @DisplayName("strKeyDecode should reject a truncated address")
    void strKeyDecode_shouldRejectTruncatedAddress() {
        assertThatThrownBy(() -> StellarAssetService.strKeyDecode("GAAAA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Stellar G-address length");
    }

    // ── Transaction hash computation tests ────────────────────────────────────

    @Test
    @DisplayName("computeTxHash should produce a 32-byte SHA-256 hash")
    void computeTxHash_shouldProduce32Bytes() {
        byte[] fakeTxXdr = new byte[]{1, 2, 3, 4, 5};

        byte[] hash = StellarAssetService.computeTxHash(
                "Test SDF Network ; September 2015", fakeTxXdr);

        assertThat(hash).hasSize(32);
    }

    @Test
    @DisplayName("computeTxHash should differ between mainnet and testnet passphrases")
    void computeTxHash_shouldDifferAcrossNetworks() {
        byte[] fakeTxXdr = new byte[]{1, 2, 3, 4, 5};

        byte[] mainnetHash = StellarAssetService.computeTxHash(
                "Public Global Stellar Network ; September 2015", fakeTxXdr);
        byte[] testnetHash = StellarAssetService.computeTxHash(
                "Test SDF Network ; September 2015", fakeTxXdr);

        assertThat(mainnetHash).isNotEqualTo(testnetHash);
    }

    // ── Ed25519 signing tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("ed25519Sign should produce a 64-byte signature")
    void ed25519Sign_shouldProduce64ByteSignature() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(seed);
        byte[] message = "test message".getBytes();

        byte[] signature = StellarAssetService.ed25519Sign(privKey, message);

        assertThat(signature).hasSize(64);
    }

    @Test
    @DisplayName("ed25519Sign should be deterministic for the same key and message")
    void ed25519Sign_shouldBeDeterministic() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(seed);
        byte[] message = "test message".getBytes();

        byte[] sig1 = StellarAssetService.ed25519Sign(privKey, message);
        byte[] sig2 = StellarAssetService.ed25519Sign(privKey, message);

        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("ed25519Sign should produce different signatures for different messages")
    void ed25519Sign_shouldDifferForDifferentMessages() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(seed);

        byte[] sig1 = StellarAssetService.ed25519Sign(privKey, "message A".getBytes());
        byte[] sig2 = StellarAssetService.ed25519Sign(privKey, "message B".getBytes());

        assertThat(sig1).isNotEqualTo(sig2);
    }

    // ── Service routing tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("createStellarAsset should fail when no Stellar chain config is enabled")
    void createStellarAsset_shouldFailWhenNoChainConfigured() {
        when(chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STELLAR))
                .thenReturn(List.of());

        CompletableFuture<StellarAssetService.StellarDeployment> future = stellarAssetService.createStellarAsset(
                UUID.randomUUID(), Network.TESTNET, "");

        assertThatThrownBy(() -> {
            try {
                future.get();
            } catch (ExecutionException ex) {
                throw ex.getCause();
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No enabled Stellar chain config");
    }

    @Test
    @DisplayName("createStellarAsset should use TESTNET chain config for TESTNET network")
    void createStellarAsset_shouldFilterToTestnetConfig() {
        ChainConfig mainnetConfig = new ChainConfig();
        mainnetConfig.setNetworkType(ChainConfig.NetworkType.MAINNET);
        mainnetConfig.setRpcUrl("https://horizon.stellar.org");

        when(chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.STELLAR))
                .thenReturn(List.of(mainnetConfig));

        CompletableFuture<StellarAssetService.StellarDeployment> future = stellarAssetService.createStellarAsset(
                UUID.randomUUID(), Network.TESTNET, "");

        assertThatThrownBy(() -> {
            try {
                future.get();
            } catch (ExecutionException ex) {
                throw ex.getCause();
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No enabled Stellar chain config for TESTNET");
    }
}
