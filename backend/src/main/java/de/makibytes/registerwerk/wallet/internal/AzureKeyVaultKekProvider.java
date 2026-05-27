package de.makibytes.registerwerk.wallet.internal;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Azure Key Vault envelope encryption for wallet DEKs.
 * Activate: REGISTERWERK_WALLET_KEK_PROVIDER=AZURE_KEY_VAULT
 * Requires: registerwerk.wallet.kms.key-id = https://VAULT.vault.azure.net/keys/KEY/VERSION
 *           AZURE_CLIENT_ID + AZURE_CLIENT_SECRET + AZURE_TENANT_ID, or Managed Identity
 */
@Component("azureKeyVaultKekProvider")
@ConditionalOnProperty(name = "registerwerk.wallet.kek-provider", havingValue = "AZURE_KEY_VAULT")
class AzureKeyVaultKekProvider implements KekProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureKeyVaultKekProvider.class);
    private static final KeyWrapAlgorithm ALGORITHM = KeyWrapAlgorithm.RSA_OAEP_256;

    private final CryptographyClient crypto;

    AzureKeyVaultKekProvider(@Value("${registerwerk.wallet.kms.key-id}") String keyId) {
        this.crypto = new CryptographyClientBuilder()
                .keyIdentifier(keyId)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        log.info("AzureKeyVaultKekProvider initialized with keyId={}", keyId);
    }

    @Override
    public String name() { return "AZURE_KEY_VAULT"; }

    @Override
    public byte[] wrap(byte[] plaintextDek) {
        return crypto.wrapKey(ALGORITHM, plaintextDek).getEncryptedKey();
    }

    @Override
    public byte[] unwrap(byte[] wrappedDek) {
        return crypto.unwrapKey(ALGORITHM, wrappedDek).getKey();
    }
}
