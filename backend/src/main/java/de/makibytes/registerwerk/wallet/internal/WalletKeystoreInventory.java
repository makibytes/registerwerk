package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.shared.EnvelopeSecretInventory;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.api.OperatorWalletRepository;
import de.makibytes.registerwerk.wallet.api.WalletStorage;
import de.makibytes.registerwerk.wallet.api.OperatorWallet.WalletType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Software-wallet keystores: the data key of each is wrapped by the platform KEK. HSM/KMS wallets hold none. */
@Component
class WalletKeystoreInventory implements EnvelopeSecretInventory {

    private static final Logger log = LoggerFactory.getLogger(WalletKeystoreInventory.class);

    private final OperatorWalletRepository wallets;
    private final WalletStorage storage;
    private final KekProvider kek;

    WalletKeystoreInventory(OperatorWalletRepository wallets, WalletStorage storage, KekProvider kek) {
        this.wallets = wallets;
        this.storage = storage;
        this.kek = kek;
    }

    @Override
    public String type() {
        return "WALLET_KEY";
    }

    @Override
    public Map<String, Long> countByKekVersion() {
        Map<String, Long> counts = new TreeMap<>();
        for (OperatorWallet w : wallets.findAll()) {
            label(w).ifPresent(l -> counts.merge(l, 1L, Long::sum));
        }
        return counts;
    }

    @Override
    public RewrapOutcome rewrapStale(int pageSize) {
        int rewrapped = 0;
        int failed = 0;
        Optional<String> active = kek.activeVersion();
        for (OperatorWallet w : wallets.findAll()) {
            Optional<String> label = label(w);
            if (label.isEmpty() || active.isEmpty() || active.get().equals(label.get())) {
                continue;
            }
            try {
                if (storage.rewrapDek(w.getKeystorePath(), w.getType() == WalletType.EVM)) {
                    rewrapped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("KEK re-wrap failed for wallet {} ({})", w.getId(), e.getClass().getSimpleName());
            }
        }
        return new RewrapOutcome(rewrapped, failed);
    }

    /** Version label of the wallet's wrapped DEK; empty for wallets that hold no wrapped DEK. */
    private Optional<String> label(OperatorWallet w) {
        if (w.getCustodyType().isOpaque()) {
            return Optional.empty();
        }
        Optional<byte[]> wrapped = storage.wrappedDekOf(w.getKeystorePath(), w.getType() == WalletType.EVM);
        if (wrapped.isEmpty()) {
            return Optional.empty();
        }
        if (kek.activeVersion().isEmpty()) {
            return Optional.of("unversioned");
        }
        try {
            return Optional.of(kek.versionOf(wrapped.get()).orElse("unknown"));
        } catch (RuntimeException e) {
            return Optional.of("unknown");
        }
    }
}
