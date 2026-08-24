package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.wallet.api.EvmSigner;
import java.util.UUID;

/**
 * SPI for signing blockchain transactions.
 * Implemented by the wallet module ({@code WalletSignerImpl}).
 */
public interface SignerPort {
    EvmSigner evmSignerFor(UUID walletId);
    byte[] solanaPrivateKey(UUID walletId);
}
