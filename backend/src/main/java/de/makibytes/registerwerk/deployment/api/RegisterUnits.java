package de.makibytes.registerwerk.deployment.api;

import de.makibytes.registerwerk.shared.RegisterUnitsException;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The register-unit convention (Wave 0b C5): <strong>the register counts whole units</strong>.
 *
 * <p>{@code asset_holder.nominal_amount} and {@code token_transfer.amount} are the token's RAW base units (the
 * indexer writes them unscaled, see V16). Every flow that turns them into money or into a mint - coupon /
 * redemption / dividend maths ({@code amountPerUnit x nominal}), the primary-market mint
 * ({@code allocated} is sent as-is), secondary trading (quantity and price per unit) - reads them as WHOLE units. On
 * an 18-decimals token that is wrong by a factor of 10^18. Instead of scaling in four places, bond / fund tokens are
 * deployed with {@code decimals = 0} (one token = one unit) and the deployment row records what was deployed
 * ({@link AssetDeployment#getTokenDecimals()}); these methods are the single guard that refuses a flow on any asset
 * with a live deployment that does not report exactly 0. Unknown (null) decimals are refused like any other value.
 *
 * <p>An asset with no live deployment (off-chain register) has nothing to scale and passes. {@code FAILED}
 * deployments never held a token and are ignored; {@code PENDING} ones count (their decimals are fixed at creation).
 */
public final class RegisterUnits {

    /** The only decimals value a register-unit flow accepts. */
    public static final int WHOLE_UNIT_DECIMALS = 0;

    private RegisterUnits() {}

    /**
     * Decimals a NEW deployment of {@code standard} is created with by this code base, recorded on the deployment
     * row. {@code 0} for tokens deployed as whole-unit register tokens and for integer-only contracts; the
     * contract's fixed value for standards that cannot be deployed whole (Cairo ERC-20 returns a hard-coded 18,
     * Stellar assets are fixed at 7); {@code null} where the decimals are not ours to choose (vault shares follow
     * their underlying asset, confidential amounts are encrypted, Canton tokens are not deployable here) - refused.
     */
    public static Integer deployedDecimals(TokenStandard standard) {
        if (standard == null) {
            return null;
        }
        return switch (standard) {
            case ERC20, ERC3643, ERC721, ERC1155, ERC3525, STARKNET_ERC3525, SPL, SPL_2022, SPL_2022_BOND,
                 DAML_BOND_FIXED, DAML_BOND_FLOATING, DAML_BOND_ZERO -> WHOLE_UNIT_DECIMALS;
            case STARKNET_ERC20 -> 18;
            case STELLAR_ASSET -> 7;
            case ERC4626, ERC7540, CONF_ERC20, CONF_ERC3643, SPL_2022_CONFIDENTIAL, CANTON_TOKEN -> null;
        };
    }

    /** True when this deployment's token counts in whole units. */
    public static boolean isWholeUnit(AssetDeployment deployment) {
        return deployment != null && deployment.getTokenDecimals() != null
                && deployment.getTokenDecimals() == WHOLE_UNIT_DECIMALS;
    }

    /**
     * @throws RegisterUnitsException (409, recorded as a rejected action) when {@code deployment} is not whole-unit
     */
    public static void requireWholeUnit(AssetDeployment deployment, String operation) {
        if (!isWholeUnit(deployment)) {
            throw new RegisterUnitsException(message(operation, deployment));
        }
    }

    /**
     * The operator-facing refusal for these deployments, or empty when every live one counts in whole units. Used
     * where a refusal must be parked visibly (corporate-action snapshot) instead of thrown.
     */
    public static Optional<String> refusal(Collection<AssetDeployment> deployments, String operation) {
        if (deployments == null) {
            return Optional.empty();
        }
        String offenders = deployments.stream()
                .filter(RegisterUnits::isLive)
                .filter(d -> !isWholeUnit(d))
                .map(RegisterUnits::describe)
                .collect(Collectors.joining(", "));
        return offenders.isEmpty()
                ? Optional.empty()
                : Optional.of(operation + " refused: " + offenders + ". " + CONVENTION);
    }

    /** @throws RegisterUnitsException when any live deployment of {@code assetId} is not whole-unit */
    public static void requireWholeUnits(AssetDeploymentRepository repository, UUID assetId, String operation) {
        refusal(repository.findByAssetId(assetId), operation).ifPresent(reason -> {
            throw new RegisterUnitsException(reason);
        });
    }

    private static final String CONVENTION =
            "The register counts whole units (one token = one unit): deploy bond / fund tokens with decimals=0. "
                    + "Redeploying the asset with a whole-unit token is the fix; nothing was changed.";

    private static boolean isLive(AssetDeployment deployment) {
        return deployment.getDeploymentStatus() != AssetDeployment.DeploymentStatus.FAILED;
    }

    private static String message(String operation, AssetDeployment deployment) {
        return operation + " refused: " + describe(deployment) + ". " + CONVENTION;
    }

    private static String describe(AssetDeployment deployment) {
        String decimals = deployment.getTokenDecimals() == null
                ? "decimals unknown" : "decimals=" + deployment.getTokenDecimals();
        return "deployment " + deployment.getId() + " has " + decimals + " (expected decimals=0)";
    }
}
