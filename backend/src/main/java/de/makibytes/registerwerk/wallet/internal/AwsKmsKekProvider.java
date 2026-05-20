package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.ReEncryptRequest;

/**
 * KMS envelope encryption using AWS KMS.
 * Activate: REGISTERWERK_WALLET_KEK_PROVIDER=AWS_KMS
 * Requires: AWS_KMS_KEY_ID (ARN or alias/registerwerk-wallet-kek)
 *           AWS credentials via instance profile / IAM role (preferred) or env vars
 */
@Component("awsKmsKekProvider")
@ConditionalOnProperty(name = "registerwerk.wallet.kek-provider", havingValue = "AWS_KMS")
class AwsKmsKekProvider implements KekProvider {

    private static final Logger log = LoggerFactory.getLogger(AwsKmsKekProvider.class);

    private final KmsClient kms;
    private final String keyId;

    AwsKmsKekProvider(
            @Value("${registerwerk.wallet.kms.key-id}") String keyId) {
        this.kms   = KmsClient.create(); // uses DefaultCredentialsProvider chain
        this.keyId = keyId;
        log.info("AwsKmsKekProvider initialized with keyId={}", keyId);
    }

    @Override
    public String name() { return "AWS_KMS"; }

    @Override
    public byte[] wrap(byte[] plaintextDek) {
        var response = kms.encrypt(EncryptRequest.builder()
                .keyId(keyId)
                .plaintext(SdkBytes.fromByteArray(plaintextDek))
                .build());
        return response.ciphertextBlob().asByteArray();
    }

    @Override
    public byte[] unwrap(byte[] wrappedDek) {
        var response = kms.decrypt(DecryptRequest.builder()
                .keyId(keyId)
                .ciphertextBlob(SdkBytes.fromByteArray(wrappedDek))
                .build());
        return response.plaintext().asByteArray();
    }

    @Override
    public byte[] rewrap(byte[] wrappedDek) {
        var response = kms.reEncrypt(ReEncryptRequest.builder()
                .ciphertextBlob(SdkBytes.fromByteArray(wrappedDek))
                .destinationKeyId(keyId)
                .build());
        return response.ciphertextBlob().asByteArray();
    }
}
