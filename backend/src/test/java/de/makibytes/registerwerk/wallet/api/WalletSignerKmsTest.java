package de.makibytes.registerwerk.wallet.api;

import de.makibytes.registerwerk.wallet.internal.KmsEvmSigner;
import de.makibytes.registerwerk.wallet.internal.KmsSignerService;
import de.makibytes.registerwerk.wallet.internal.Pkcs11HsmService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("WalletSigner - KMS custody resolves to the cloud-KMS signer, cached per wallet")
class WalletSignerKmsTest {

    private static final String KEY = "projects/p/locations/l/keyRings/r/cryptoKeys/k/cryptoKeyVersions/1";
    private static final String ADDRESS = "0x" + "ab".repeat(20);

    private final WalletChainDefaultRepository defaults = mock(WalletChainDefaultRepository.class);
    private final OperatorWalletRepository wallets = mock(OperatorWalletRepository.class);
    private final WalletStorage storage = mock(WalletStorage.class);
    private final KmsSignerService kms = mock(KmsSignerService.class);
    private final WalletSigner signer = new WalletSigner(defaults, wallets, storage, mock(Pkcs11HsmService.class), kms);

    private UUID kmsWalletDefault() {
        UUID chain = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        OperatorWallet wallet = mock(OperatorWallet.class);
        when(wallet.getId()).thenReturn(walletId);
        when(wallet.getName()).thenReturn("kms");
        when(wallet.getCustodyType()).thenReturn(OperatorWallet.CustodyType.KMS);
        when(wallet.getKeyReference()).thenReturn(KEY);
        when(wallet.getAddress()).thenReturn(ADDRESS);
        WalletChainDefault def = mock(WalletChainDefault.class);
        when(def.getWallet()).thenReturn(wallet);
        when(defaults.findByChainConfigId(chain)).thenReturn(Optional.of(def));
        when(wallets.findById(walletId)).thenReturn(Optional.of(wallet));
        return chain;
    }

    @Test
    @DisplayName("a KMS wallet is signed by the KMS signer (no keystore read) and the signer is cached")
    void kmsWalletUsesKmsSigner() {
        UUID chain = kmsWalletDefault();
        KmsEvmSigner kmsSigner = mock(KmsEvmSigner.class);
        when(kms.signerFor(KEY, ADDRESS)).thenReturn(kmsSigner);

        assertThat(signer.evmSignerForChain(chain)).isSameAs(kmsSigner);
        assertThat(signer.evmSignerForChain(chain)).isSameAs(kmsSigner);

        verify(kms, times(1)).signerFor(KEY, ADDRESS);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("when KMS signing is not enabled/reachable the failure surfaces instead of a software fallback")
    void kmsFailureSurfaces() {
        UUID chain = kmsWalletDefault();
        when(kms.signerFor(KEY, ADDRESS)).thenThrow(new IllegalStateException("KMS signer support is not enabled"));
        assertThatThrownBy(() -> signer.evmSignerForChain(chain)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not enabled");
        verifyNoInteractions(storage);
    }
}
