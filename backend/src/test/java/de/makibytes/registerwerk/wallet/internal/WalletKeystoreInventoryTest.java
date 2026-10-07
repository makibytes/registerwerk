package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.shared.EnvelopeSecretInventory.RewrapOutcome;
import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.api.OperatorWalletRepository;
import de.makibytes.registerwerk.wallet.api.WalletStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Wallet keystore KEK inventory")
class WalletKeystoreInventoryTest {

    private static final String K1 = "k1-0123456789-0123456789-0123456789-aaaa";
    private static final String K2 = "k2-0123456789-0123456789-0123456789-bbbb";

    private static EnvVarKekProvider provider(String version, String key, Map<String, String> previous) {
        WalletProperties p = new WalletProperties();
        p.setMasterKey(key);
        p.setMasterKeyVersion(version);
        p.setPreviousMasterKeys(previous);
        return new EnvVarKekProvider(p);
    }

    private static OperatorWallet wallet(String name, String path) {
        OperatorWallet w = new OperatorWallet();
        w.setName(name);
        w.setType(OperatorWallet.WalletType.EVM);
        w.setKeystorePath(path);
        w.setCustodyType(OperatorWallet.CustodyType.SOFTWARE);
        return w;
    }

    @Test
    @DisplayName("counts wrapped DEKs per version, skips legacy keystores, re-wraps stale ones and counts failures")
    void countsAndRewraps() {
        EnvVarKekProvider v1 = provider("v1", K1, Map.of());
        EnvVarKekProvider v2 = provider("v2", K2, Map.of("v1", K1));
        byte[] onV1 = v1.wrap(new byte[32]);
        byte[] onV2 = v2.wrap(new byte[32]);
        OperatorWalletRepository repo = mock(OperatorWalletRepository.class);
        when(repo.findAll()).thenReturn(List.of(wallet("a", "a.json"), wallet("b", "b.json"),
                wallet("c", "c.json"), wallet("legacy", "legacy.json")));
        WalletStorage storage = mock(WalletStorage.class);
        when(storage.wrappedDekOf("a.json", true)).thenReturn(Optional.of(onV1));
        when(storage.wrappedDekOf("b.json", true)).thenReturn(Optional.of(onV2));
        when(storage.wrappedDekOf("c.json", true)).thenReturn(Optional.of(onV1));
        when(storage.wrappedDekOf("legacy.json", true)).thenReturn(Optional.empty());
        when(storage.rewrapDek("a.json", true)).thenReturn(true);
        when(storage.rewrapDek("c.json", true)).thenThrow(new WalletStorage.WalletStorageException("boom"));
        WalletKeystoreInventory inv = new WalletKeystoreInventory(repo, storage, v2);

        assertThat(inv.type()).isEqualTo("WALLET_KEY");
        assertThat(inv.countByKekVersion()).isEqualTo(Map.of("v1", 2L, "v2", 1L));
        assertThat(inv.rewrapStale(100)).isEqualTo(new RewrapOutcome(1, 1));
        verify(storage, never()).rewrapDek("b.json", true);
    }
}
