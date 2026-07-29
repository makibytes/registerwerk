package de.makibytes.registerwerk.blockchain.api;

import java.math.BigInteger;

/**
 * Backend-side client for Registerwerk's own `zama-relayer` sidecar (`zama-relayer/` at the repo
 * root — wraps the real {@code @zama-fhe/relayer-sdk}; Zama publishes no Java/JVM client, hence
 * the sidecar). This interface covers exactly the flows that genuinely have no browser/wallet in
 * the loop:
 * <ul>
 *   <li>{@link #encryptInput} — server-side equivalent of the SDK's
 *       {@code createEncryptedInput(...).encrypt()}, producing a ciphertext handle plus the ZK
 *       input proof a confidential contract call requires. Used for operator/issuer-initiated
 *       confidential mint and confidential forceBurn ({@code ConfidentialTokenAdminService},
 *       {@code TokenAdminService.confidentialForceBurn}).</li>
 *   <li>{@link #requestOperatorDecrypt} — headless decryption for the registry operator/auditor
 *       viewer role (e.g. {@code ConfidentialBalanceReconciliationService} comparing an on-chain
 *       confidential balance against the register's plaintext {@code AssetHolder.nominalAmount}).
 *       The sidecar generates a fresh ephemeral FHE keypair, builds the KMS's
 *       {@code UserDecryptRequestVerification} EIP-712 payload, self-signs it with a dedicated
 *       decrypt-only key (never an on-chain transaction-signing wallet), and completes the
 *       decrypt — all in one round trip. Only succeeds if that key's address is already a
 *       registered viewer ({@code ConfidentialERC20.isViewer}) on the token in question.</li>
 *   <li>{@link #requestPublicDecrypt} — the oracle/public decryption path (see
 *       {@code ConfidentialERC20.requestSupplyDisclosure}/{@code callbackSupplyDisclosure}):
 *       submits a ciphertext handle to the Relayer's KMS and returns the cleartext, for values a
 *       contract has already made publicly decryptable — no signature/viewer check needed.</li>
 * </ul>
 *
 * <p><b>What this deliberately does NOT cover:</b> an investor/issuer/operator revealing their
 * OWN balance in the browser, and an investor encrypting a confidential-transfer amount, are
 * both handled entirely client-side by the frontends' {@code FheClientService} calling
 * {@code @zama-fhe/relayer-sdk}'s browser build directly against Zama's real relayer — this
 * backend/sidecar is never in that path, by design (the backend must never see a plaintext
 * balance it isn't itself authorized to view). An earlier revision of this interface had a
 * {@code requestUserDecrypt(handle, contract, user, signature)} method for a speculative
 * "backend relays someone else's signature" case; it was removed because it was structurally
 * incomplete (Zama's real {@code userDecrypt} also requires the ephemeral keypair used to build
 * the EIP-712 payload the signature covers, which that method never had a way to supply) and had
 * zero real callers — an unfinished stub is worse than no method at all.
 */
public interface ZamaRelayerClient {

    /** Whether the relayer sidecar is actually configured. */
    boolean isConfigured();

    record EncryptedInput(String ciphertextHandle, String inputProofHex) {}

    /**
     * Encrypts {@code plaintextValue} for use as an input to a confidential contract call at
     * {@code contractAddress}, authorized for {@code userAddress} (the address that will submit
     * the transaction — typically the registry's own operator wallet for operator-initiated
     * confidential actions).
     */
    EncryptedInput encryptInput(String contractAddress, String userAddress, BigInteger plaintextValue);

    /**
     * Headless decrypt of {@code ciphertextHandle} using the sidecar's dedicated operator-decrypt
     * key — see the class-level note. Fails loudly (not silently) if that key is not a registered
     * viewer on {@code contractAddress}, or if the sidecar's operator-decrypt key is unconfigured.
     */
    BigInteger requestOperatorDecrypt(String ciphertextHandle, String contractAddress);

    /**
     * Requests the Relayer's KMS publicly decrypt {@code ciphertextHandle} — used for values a
     * contract itself allowed for public decryption (see {@code TFHE.allow(..., address(this))}
     * plus {@code Gateway.requestDecryption} in the confidential contracts), not a specific
     * user's private balance.
     */
    BigInteger requestPublicDecrypt(String ciphertextHandle);
}
