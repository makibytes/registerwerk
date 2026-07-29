package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.orgidentity.api.PermissionGate;
import de.makibytes.registerwerk.orgidentity.api.WalletSignatureVerifier;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Manifest integrity + authorship: the publisher signs keccak256(manifest_raw) with a
 * wallet that is a confirmed member of their organization (EIP-191 personal_sign over
 * the hex hash string). Anyone can later recompute the hash and compare it with the
 * onchain DappRegistry anchor. Signature verification (EOA or smart-contract wallet) is
 * delegated to {@link WalletSignatureVerifier}, shared with member-wallet binding.
 */
@Service
public class ManifestSigningService {

    private final PermissionGate permissionGate;
    private final WalletSignatureVerifier signatureVerifier;

    ManifestSigningService(PermissionGate permissionGate, WalletSignatureVerifier signatureVerifier) {
        this.permissionGate = permissionGate;
        this.signatureVerifier = signatureVerifier;
    }

    /**
     * keccak256 of the manifest's raw bytes, as 0x-prefixed hex — the signing payload.
     *
     * <p><strong>Replay safety note:</strong> this hash (and the signature over it in {@link
     * #verify}) does not itself bind a chain id or listing/entity identifier — a signed
     * manifest can only be trusted for one specific listing today because {@code
     * ListingLifecycleService} enforces a <em>globally</em> unique {@code slug} and re-checks
     * {@code isWalletBoundToEntity} live at both sign-time and approve-time. If slug uniqueness
     * is ever relaxed (e.g. scoped per chain) or a listing's chain is made mutable, this
     * signature could be replayed onto a different listing the same signer is also a member of
     * — treat either change as requiring this scheme to be revisited, not an incidental side effect.
     */
    public String manifestHash(String manifestRaw) {
        return Numeric.toHexString(Hash.sha3(manifestRaw.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Verifies the signature over the manifest hash and that the signer is a bound,
     * active member wallet of the publisher's org on the listing's anchor chain.
     *
     * @throws IllegalArgumentException when the signature or signer does not check out
     */
    public void verify(String manifestRaw, String signature, String signerWallet,
                       UUID publisherEntityId, UUID chainConfigId) {
        signatureVerifier.verifyPersonalSign(chainConfigId, manifestHash(manifestRaw), signature, signerWallet);
        if (!permissionGate.isWalletBoundToEntity(signerWallet, publisherEntityId, chainConfigId)) {
            throw new IllegalArgumentException(
                    "Signer wallet " + signerWallet + " is not an active member wallet of the publisher's organization");
        }
    }
}
