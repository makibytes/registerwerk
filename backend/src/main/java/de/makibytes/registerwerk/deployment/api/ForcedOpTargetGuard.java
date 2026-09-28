package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.shared.AddressNormalizer;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Scope check for forced operations started by an {@code ASSET_TOKEN_ADMIN} grantee rather than a
 * REGISTRY_ADMIN (T3-21). The chain call is signed with the operator key, so the database is the
 * only scope control: a grantee may only target wallets that are active register entries of the
 * asset in the path. A wallet outside that register is refused with 400
 * ({@link IllegalArgumentException}). REGISTRY_ADMIN callers are not restricted here.
 */
@Component
public class ForcedOpTargetGuard {

    private final AssetHolderRepository holderRepository;

    public ForcedOpTargetGuard(AssetHolderRepository holderRepository) {
        this.holderRepository = holderRepository;
    }

    public void requireHolderWalletForGrantee(UUID assetId, String wallet, Authentication auth) {
        if (isRegistryAdmin(auth)) {
            return;
        }
        String normalized = AddressNormalizer.normalize(wallet);
        if (normalized == null || normalized.isEmpty()
                || holderRepository.findActiveByAssetIdAndWalletAddress(assetId, normalized).isEmpty()) {
            throw new IllegalArgumentException("Wallet " + wallet + " is not an active register entry of asset "
                    + assetId + " — an ASSET_TOKEN_ADMIN grant only covers this asset's holders.");
        }
    }

    public void requireHolderWalletsForGrantee(UUID assetId, Collection<String> wallets, Authentication auth) {
        if (isRegistryAdmin(auth) || wallets == null) {
            return;
        }
        wallets.forEach(w -> requireHolderWalletForGrantee(assetId, w, auth));
    }

    private static boolean isRegistryAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_REGISTRY_ADMIN".equals(a.getAuthority()));
    }
}
