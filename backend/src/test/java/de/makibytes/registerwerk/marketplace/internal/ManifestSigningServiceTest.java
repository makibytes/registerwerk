package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.orgidentity.api.PermissionGate;
import de.makibytes.registerwerk.orgidentity.api.WalletSignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Real-signature coverage for the manifest-signing convention this module documents and every
 * other test mocks away: EIP-191 {@code personal_sign} over the 0x-hex STRING of {@code
 * keccak256(manifest_raw_bytes)} — not the raw hash bytes. {@code ManifestSigningService} had
 * zero dedicated test before this; a regression in that hex-string-vs-raw-bytes convention would
 * have silently broken the whole marketplace trust model with nothing catching it (ERC-3643
 * review's sibling finding — the same class of bug — is exactly what a mocked-away signer can't
 * detect). Uses a real EIP-191 ECDSA verifier (mirroring {@code WalletSignatureVerifierImplTest})
 * rather than mocking {@link WalletSignatureVerifier} itself, so the actual recovery runs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManifestSigningService — real EIP-191 signature over manifestHash's hex string")
class ManifestSigningServiceTest {

    @Mock
    private PermissionGate permissionGate;

    private final WalletSignatureVerifier realVerifier = new RealEcdsaWalletSignatureVerifier();

    @Test
    @DisplayName("accepts a signature over manifestHash(manifestRaw)'s hex STRING (the documented convention)")
    void verify_acceptsSignatureOverHexStringConvention() throws Exception {
        ManifestSigningService service = new ManifestSigningService(permissionGate, realVerifier);
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String wallet = "0x" + Keys.getAddress(keyPair);
        UUID entityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        String manifestRaw = "{\"slug\":\"bond-desk\",\"version\":\"1.0.0\"}";

        String hashHex = service.manifestHash(manifestRaw);
        String signature = sign(hashHex, keyPair); // signs the hex STRING's UTF-8 bytes, per convention

        lenient().when(permissionGate.isWalletBoundToEntity(wallet, entityId, chainConfigId)).thenReturn(true);

        service.verify(manifestRaw, signature, wallet, entityId, chainConfigId);
        // no exception => the documented convention verifies correctly
    }

    @Test
    @DisplayName("rejects a signature over the raw hash BYTES instead of the hex string — the exact regression this test exists to catch")
    void verify_rejectsSignatureOverRawHashBytes() throws Exception {
        ManifestSigningService service = new ManifestSigningService(permissionGate, realVerifier);
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String wallet = "0x" + Keys.getAddress(keyPair);
        UUID entityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        String manifestRaw = "{\"slug\":\"bond-desk\",\"version\":\"1.0.0\"}";

        // Sign the RAW 32 hash bytes directly (the wrong convention) instead of the hex string.
        byte[] rawHash = Hash.sha3(manifestRaw.getBytes(StandardCharsets.UTF_8));
        Sign.SignatureData sig = Sign.signPrefixedMessage(rawHash, keyPair);
        byte[] sigBytes = new byte[65];
        System.arraycopy(sig.getR(), 0, sigBytes, 0, 32);
        System.arraycopy(sig.getS(), 0, sigBytes, 32, 32);
        sigBytes[64] = sig.getV()[0];
        String wrongSignature = Numeric.toHexString(sigBytes);

        assertThatThrownBy(() ->
                service.verify(manifestRaw, wrongSignature, wallet, entityId, chainConfigId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects when the signer is not a bound member wallet of the publisher's org")
    void verify_rejectsUnboundSigner() throws Exception {
        ManifestSigningService service = new ManifestSigningService(permissionGate, realVerifier);
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String wallet = "0x" + Keys.getAddress(keyPair);
        UUID entityId = UUID.randomUUID();
        UUID chainConfigId = UUID.randomUUID();
        String manifestRaw = "{\"slug\":\"bond-desk\",\"version\":\"1.0.0\"}";

        String signature = sign(service.manifestHash(manifestRaw), keyPair);
        when(permissionGate.isWalletBoundToEntity(wallet, entityId, chainConfigId)).thenReturn(false);

        assertThatThrownBy(() -> service.verify(manifestRaw, signature, wallet, entityId, chainConfigId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an active member wallet");
    }

    private static String sign(String message, ECKeyPair keyPair) {
        Sign.SignatureData signature = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), keyPair);
        byte[] out = new byte[65];
        System.arraycopy(signature.getR(), 0, out, 0, 32);
        System.arraycopy(signature.getS(), 0, out, 32, 32);
        out[64] = signature.getV()[0];
        return Numeric.toHexString(out);
    }

    /** Real EIP-191 ECDSA-only recovery (no ERC-1271 branch needed for this test's EOA wallets). */
    private static final class RealEcdsaWalletSignatureVerifier implements WalletSignatureVerifier {
        @Override
        public void verifyPersonalSign(UUID chainConfigId, String message, String signatureHex, String claimedWallet) {
            byte[] sigBytes = Numeric.hexStringToByteArray(signatureHex);
            if (sigBytes.length != 65) {
                throw new IllegalArgumentException("signature must be 65 bytes");
            }
            byte[] r = Arrays.copyOfRange(sigBytes, 0, 32);
            byte[] s = Arrays.copyOfRange(sigBytes, 32, 64);
            byte[] v = new byte[]{sigBytes[64]};
            try {
                BigInteger recoveredKey = Sign.signedPrefixedMessageToKey(
                        message.getBytes(StandardCharsets.UTF_8), new Sign.SignatureData(v, r, s));
                String recoveredAddress = "0x" + Keys.getAddress(recoveredKey);
                if (!recoveredAddress.equalsIgnoreCase(claimedWallet)) {
                    throw new IllegalArgumentException(
                            "Signature not produced by wallet " + claimedWallet);
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid signature: " + e.getMessage(), e);
            }
        }
    }
}
