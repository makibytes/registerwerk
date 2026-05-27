package de.makibytes.registerwerk.wallet.internal;

import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Google Cloud KMS envelope encryption for wallet DEKs.
 * Activate: REGISTERWERK_WALLET_KEK_PROVIDER=GCP_KMS
 * Requires: registerwerk.wallet.kms.key-id = projects/P/locations/L/keyRings/R/cryptoKeys/K
 *           GOOGLE_APPLICATION_CREDENTIALS or GKE Workload Identity
 */
@Component("gcpKmsKekProvider")
@ConditionalOnProperty(name = "registerwerk.wallet.kek-provider", havingValue = "GCP_KMS")
class GcpKmsKekProvider implements KekProvider {

    private static final Logger log = LoggerFactory.getLogger(GcpKmsKekProvider.class);
    private final String resourceName;

    GcpKmsKekProvider(@Value("${registerwerk.wallet.kms.key-id}") String resourceName) {
        this.resourceName = resourceName;
        log.info("GcpKmsKekProvider initialized with resourceName={}", resourceName);
    }

    @Override
    public String name() { return "GCP_KMS"; }

    @Override
    public byte[] wrap(byte[] plaintextDek) {
        try (KeyManagementServiceClient client = KeyManagementServiceClient.create()) {
            return client.encrypt(resourceName, ByteString.copyFrom(plaintextDek))
                    .getCiphertext()
                    .toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("GCP KMS wrap failed for key " + resourceName, e);
        }
    }

    @Override
    public byte[] unwrap(byte[] wrappedDek) {
        try (KeyManagementServiceClient client = KeyManagementServiceClient.create()) {
            return client.decrypt(resourceName, ByteString.copyFrom(wrappedDek))
                    .getPlaintext()
                    .toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("GCP KMS unwrap failed for key " + resourceName, e);
        }
    }
}
