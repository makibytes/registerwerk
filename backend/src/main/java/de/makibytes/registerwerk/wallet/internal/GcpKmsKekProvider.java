package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Google Cloud KMS envelope encryption for wallet DEKs.
 * Activate: registerwerk.wallet.kek-provider=GCP_KMS
 * Requires: registerwerk.wallet.kms.key-id = projects/P/locations/L/keyRings/R/cryptoKeys/K
 *           GOOGLE_APPLICATION_CREDENTIALS or GKE Workload Identity
 *
 * Requires: com.google.cloud:google-cloud-kms (add to pom.xml when activating).
 * Stub implementation — wire real GCP SDK calls when GCP_KMS profile activated.
 */
@Component("gcpKmsKekProvider")
@ConditionalOnProperty(name = "registerwerk.wallet.kek-provider", havingValue = "GCP_KMS")
class GcpKmsKekProvider implements KekProvider {

    private static final Logger log = LoggerFactory.getLogger(GcpKmsKekProvider.class);
    private final String resourceName;

    GcpKmsKekProvider(@Value("${registerwerk.wallet.kms.key-id}") String resourceName) {
        this.resourceName = resourceName;
        log.info("GcpKmsKekProvider initialized with resourceName={}", resourceName);
        // TODO: initialize com.google.cloud.kms.v1.KeyManagementServiceClient
    }

    @Override
    public String name() { return "GCP_KMS"; }

    @Override
    public byte[] wrap(byte[] plaintextDek) {
        // TODO: implement using GCP KMS encrypt
        // try (KeyManagementServiceClient client = KeyManagementServiceClient.create()) {
        //     EncryptResponse response = client.encrypt(resourceName, ByteString.copyFrom(plaintextDek));
        //     return response.getCiphertext().toByteArray();
        // }
        throw new UnsupportedOperationException(
            "GcpKmsKekProvider: wire com.google.cloud:google-cloud-kms and implement wrap/unwrap.");
    }

    @Override
    public byte[] unwrap(byte[] wrappedDek) {
        // TODO: implement using GCP KMS decrypt
        throw new UnsupportedOperationException(
            "GcpKmsKekProvider: wire com.google.cloud:google-cloud-kms and implement wrap/unwrap.");
    }
}
