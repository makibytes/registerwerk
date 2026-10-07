package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.Erc3525AdminPort;
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Maps a token deployment to the admin path that can freeze/unfreeze a wallet on it, for the §16 eWpG Sperrvermerk
 * sync (H5). Every path goes through the durable outbox gateway and returns the tracked
 * {@code blockchain_transaction} id, so the freeze outcome can be read from the transaction status:
 * <ul>
 *   <li>{@link Path#SUITE} - ERC-3643 (T-REX): {@code Erc3643LifecycleService} -> {@code setAddressFrozen};</li>
 *   <li>{@link Path#ERC3525_ADMIN} - ERC-3525: {@code Erc3525AdminPort} -> {@code EwpgCompliance.freezeAddress};</li>
 *   <li>{@link Path#TOKEN_ADMIN} - ERC-20/721/1155, the ERC-4626/7540 vault shares and the confidential
 *       ERC-3643: {@code TokenAdminPort};</li>
 *   <li>{@link Path#UNSUPPORTED} - everything else, with the reason the operator is shown.</li>
 * </ul>
 *
 * <p>UNSUPPORTED is deliberate, not a gap papered over: the non-EVM chains have no durable submission and the
 * transaction poller reads EVM receipts only, so a freeze there could be submitted but never confirmed or failed
 * (the exact blind spot this sync exists to remove); and the contracts of some standards have no freeze at all.
 */
@Component
class SperrvermerkFreezeDispatcher {

    enum Path { SUITE, TOKEN_ADMIN, ERC3525_ADMIN, UNSUPPORTED }

    /** The resolved path; {@code suiteId} for {@link Path#SUITE}, {@code unsupportedReason} for {@link Path#UNSUPPORTED}. */
    record Route(Path path, UUID suiteId, String unsupportedReason) {
        static Route of(Path path) {
            return new Route(path, null, null);
        }

        boolean supported() {
            return path != Path.UNSUPPORTED;
        }
    }

    private static final Set<Chain> NON_EVM_CHAINS = EnumSet.of(Chain.SOLANA, Chain.STARKNET, Chain.STELLAR, Chain.CANTON);

    private final Erc3643SuiteRepository suites;
    private final AssetLookupPort assets;
    private final Erc3643LifecycleService erc3643;
    private final TokenAdminPort tokenAdmin;
    private final Erc3525AdminPort erc3525Admin;

    SperrvermerkFreezeDispatcher(Erc3643SuiteRepository suites, AssetLookupPort assets,
                                 Erc3643LifecycleService erc3643, TokenAdminPort tokenAdmin,
                                 Erc3525AdminPort erc3525Admin) {
        this.suites = suites;
        this.assets = assets;
        this.erc3643 = erc3643;
        this.tokenAdmin = tokenAdmin;
        this.erc3525Admin = erc3525Admin;
    }

    /** True for a deployment that has a token on-chain (a PENDING or FAILED deployment has nothing to freeze). */
    static boolean isLive(AssetDeployment dep) {
        return dep.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED
                && dep.getContractAddress() != null
                && !dep.getContractAddress().startsWith("0x-PENDING");
    }

    Route route(AssetDeployment dep) {
        Chain chain = dep.getChain();
        if (chain != null && NON_EVM_CHAINS.contains(chain)) {
            return new Route(Path.UNSUPPORTED, null, nonEvmReason(chain));
        }
        Optional<Erc3643Suite> suite = suites.findByAssetDeploymentId(dep.getId());
        if (suite.isPresent()) {
            return new Route(Path.SUITE, suite.get().getId(), null);
        }
        TokenStandard standard = assets.findById(dep.getAssetId()).map(AssetLookupPort.AssetInfo::tokenStandard).orElse(null);
        if (standard == null) {
            // Unknown asset: let the token admin service fail loudly (recorded as FAILED) rather than assume.
            return Route.of(Path.TOKEN_ADMIN);
        }
        return switch (standard) {
            case ERC20, ERC721, ERC1155, ERC4626, ERC7540, CONF_ERC3643,
                 // a plain ERC-3643 deployment without a suite row is a data problem: TokenAdminService refuses it
                 // with a precise message, which is recorded as FAILED (never silently skipped)
                 ERC3643 -> Route.of(Path.TOKEN_ADMIN);
            case ERC3525 -> Route.of(Path.ERC3525_ADMIN);
            case CONF_ERC20 -> new Route(Path.UNSUPPORTED, null,
                    "ConfidentialERC20 has no freeze function: a holder of this token cannot be frozen on-chain. "
                            + "Pause the token or rely on the register-level block and the operator's refusal to mint/transfer.");
            default -> new Route(Path.UNSUPPORTED, null,
                    standard + " is a non-EVM standard with no automated, outcome-tracked freeze: " + nonEvmHint());
        };
    }

    UUID freeze(Route route, AssetDeployment dep, String wallet, String reason) {
        return switch (route.path()) {
            case SUITE -> erc3643.freezeAddress(route.suiteId(), wallet, SperrvermerkFreezeService.SYSTEM_ACTOR, "SYSTEM");
            case ERC3525_ADMIN -> erc3525Admin.freezeAddress(dep.getId(), wallet, reason,
                    SperrvermerkFreezeService.SYSTEM_ACTOR, "SYSTEM");
            case TOKEN_ADMIN -> tokenAdmin.freezeAddress(dep.getId(), wallet, reason, reason,
                    SperrvermerkFreezeService.SYSTEM_ACTOR, "SYSTEM");
            case UNSUPPORTED -> throw new UnsupportedOperationException(route.unsupportedReason());
        };
    }

    UUID release(Route route, AssetDeployment dep, String wallet) {
        return switch (route.path()) {
            case SUITE -> erc3643.unfreezeAddressForBlockLift(route.suiteId(), wallet);
            case ERC3525_ADMIN -> erc3525Admin.unfreezeAfterBlockLift(dep.getId(), wallet);
            case TOKEN_ADMIN -> tokenAdmin.unfreezeAfterBlockLift(dep.getId(), wallet);
            case UNSUPPORTED -> throw new UnsupportedOperationException(route.unsupportedReason());
        };
    }

    private static String nonEvmReason(Chain chain) {
        return switch (chain) {
            case SOLANA -> "Solana (SPL / Token-2022): FreezeAccount works per token account and is not wired to "
                    + "wallet-level propagation; " + nonEvmHint();
            case STARKNET -> "Starknet: the Cairo contracts expose freeze_address, but Starknet invokes are not in the "
                    + "durable outbox and their receipts are not tracked; " + nonEvmHint();
            case STELLAR -> "Stellar: a freeze is a trustline authorization change (AUTH_REVOCABLE), not wired to "
                    + "wallet-level propagation; " + nonEvmHint();
            case CANTON -> "Canton / Daml: the Registerwerk templates have no holder-level freeze that the register can "
                    + "drive; " + nonEvmHint();
            default -> "Chain " + chain + " has no automated freeze; " + nonEvmHint();
        };
    }

    private static String nonEvmHint() {
        return "no freeze outcome could be confirmed, so none is attempted. The register-level block applies; "
                + "an operator must freeze the wallet through the chain's admin tooling and verify it.";
    }
}
