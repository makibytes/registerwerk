package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Azure Key Vault envelope encryption for wallet DEKs.
 * Activate: registerwerk.wallet.kek-provider=AZURE_KEY_VAULT
 * Requires: registerwerk.wallet.kms.key-id = https://VAULT.vault.azure.net/keys/KEY/VERSION
 *           AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID (or Managed Identity)
 *
 * Requires: com.azure:azure-security-keyvault-keys (add to pom.xml when activating).
 * Stub implementation — wire real Azure SDK calls when AZURE_KEY_VAULT profile activated.
 */
@Component("azureKeyVaultKekProvider")
@ConditionalOnProperty(name = "registerwerk.wallet.kek-provider", havingValue = "AZURE_KEY_VAULT")
class AzureKeyVaultKekProvider implements KekProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureKeyVaultKekProvider.class);

    private final String keyId;

    AzureKeyVaultKekProvider(@Value("${registerwerk.wallet.kms.key-id}") String keyId) {
        this.keyId = keyId;
        log.info("AzureKeyVaultKekProvider initialized with keyId={}", keyId);
        // TODO: initialize com.azure.security.keyvault.keys.KeyClient via DefaultAzureCredential
        // KeyClient client = new KeyClientBuilder().vaultUrl(vaultUrl).credential(new DefaultAzureCredentialBuilder().build()).buildClient();
    }

    @Override
    public String name() { return "AZURE_KEY_VAULT"; }

    @Override
    public byte[] wrap(byte[] plaintextDek) {
        // TODO: implement using Azure Key Vault wrapKey operation
        // CryptographyClient crypto = new CryptographyClientBuilder().keyIdentifier(keyId).credential(...).buildClient();
        // return crypto.wrapKey(KeyWrapAlgorithm.RSA_OAEP, plaintextDek).getEncryptedKey();
        throw new UnsupportedOperationException(
            "AzureKeyVaultKekProvider: wire com.azure:azure-security-keyvault-keys and implement wrap/unwrap.");
    }

    @Override
    public byte[] unwrap(byte[] wrappedDek) {
        // TODO: implement using Azure Key Vault unwrapKey operation
        throw new UnsupportedOperationException(
            "AzureKeyVaultKekProvider: wire com.azure:azure-security-keyvault-keys and implement wrap/unwrap.");
    }
}
