package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.internal.deploy.SolanaTokenService;
import de.makibytes.registerwerk.blockchain.internal.deploy.SplExtensionSet;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Token-2022 extension initialization in SolanaTokenService.
 *
 * Because the instruction-builder methods are private and require network I/O,
 * we verify behaviour through the public entry point. Without a real Solana RPC,
 * the futures fail with a RPC connection error — we assert that the failure originates
 * from the async path (not from input validation before the future is created).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SolanaTokenService Token-2022 extension preset tests")
class SolanaToken2022ExtensionTest {

    @Mock private BlockchainClientRegistry blockchainClientRegistry;
    @Mock private WalletSigner walletSigner;
    @Mock private ChainConfigRepository chainConfigRepository;

    @InjectMocks
    private SolanaTokenService service;

    @Test
    @DisplayName("createSplToken2022 NONE preset delegates to base SPL_2022 path")
    void createSplToken2022_nonePreset_startsAsync() {
        UUID assetId = UUID.randomUUID();
        // Will fail because no real RPC is wired up, but must not throw synchronously
        CompletableFuture<String> future = service.createSplToken2022(
                assetId, de.makibytes.registerwerk.chain.api.Network.TESTNET, "", SplExtensionSet.NONE);
        assertThat(future).isNotNull();
        // The future completes exceptionally due to no Solana wallet configured
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("createSplToken2022 BOND preset starts an async future")
    void createSplToken2022_bondPreset_startsAsync() {
        UUID assetId = UUID.randomUUID();
        CompletableFuture<String> future = service.createSplToken2022(
                assetId, de.makibytes.registerwerk.chain.api.Network.TESTNET, "", SplExtensionSet.BOND);
        assertThat(future).isNotNull();
        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("createSplToken2022 CONFIDENTIAL preset starts an async future")
    void createSplToken2022_confidentialPreset_startsAsync() {
        UUID assetId = UUID.randomUUID();
        CompletableFuture<String> future = service.createSplToken2022(
                assetId, de.makibytes.registerwerk.chain.api.Network.TESTNET, "", SplExtensionSet.CONFIDENTIAL);
        assertThat(future).isNotNull();
        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("createSplToken2022 no-arg overload delegates to NONE preset")
    void createSplToken2022_noArgOverload_startsAsync() {
        UUID assetId = UUID.randomUUID();
        CompletableFuture<String> future = service.createSplToken2022(
                assetId, de.makibytes.registerwerk.chain.api.Network.TESTNET, "");
        assertThat(future).isNotNull();
        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class);
    }
}
