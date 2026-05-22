package de.makibytes.registerwerk.blockchain.api;

import org.web3j.crypto.Credentials;
import java.util.UUID;

/**
 * SPI for signing blockchain transactions.
 * Implemented by the wallet module ({@code WalletSignerImpl}).
 */
public interface SignerPort {
    Credentials credentialsFor(UUID walletId);
    byte[] solanaPrivateKey(UUID walletId);
}
