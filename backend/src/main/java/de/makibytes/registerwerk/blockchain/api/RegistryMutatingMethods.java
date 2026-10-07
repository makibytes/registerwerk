package de.makibytes.registerwerk.blockchain.api;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The explicit list of contract functions the backend signs, classified for second-source confirmation
 * (H9). A transaction whose function is registry-mutating is only completed when a second, independent
 * RPC node agrees with the receipt ({@link SecondSourceConfirmer}).
 *
 * <p>This used to be a substring match ({@code "freeze"}, {@code "mint"}, ...), which silently missed
 * {@code setAddressFrozen} (the court-ordered freeze), {@code setMaxBalance}, {@code setNavPerShare},
 * {@code addModule} and more. The classification is now by exact function name and <strong>fails
 * closed</strong>: a name that is in neither set (a function added later and not classified, or a method
 * recorded without a name) is treated as registry-mutating. {@code RegistryMutatingMethodsCoverageTest}
 * scans the backend's call sites so a new function cannot be forgotten, and checks the classification
 * against the contract ABIs when they are built ({@code contracts/out}).
 *
 * <p>The recorded name of an EVM transaction is the Solidity function name
 * ({@code Function#getName()}), for the durable outbox and the direct paths alike.
 */
public final class RegistryMutatingMethods {

    /**
     * State-changing calls that change, restrict, release or evidence a register entry, an entitlement or the
     * rules and identities behind them: token supply, forced moves, freezes, pauses, whitelists, identity /
     * claim / trusted-issuer registries, compliance modules and their limits, supply caps and NAV, vault
     * request fulfilment, ownership, contract deployments that create an instrument or an identity, and the
     * ecosystem's organisation / permission / dApp-registry writes.
     */
    public static final Set<String> MUTATING = lower(
            // supply and forced moves
            "mint", "burn", "batchMint", "batchBurn", "forceBurn", "forceBurnSingle", "forceBurnValue",
            "forcedTransfer", "forcedTransferSingle", "forcedTransferValue", "batchForcedTransfer", "forcedApprove",
            "confidentialMint", "confidentialBurn",
            // freezes and pauses (court-ordered freezes included)
            "freezeAddress", "unfreezeAddress", "setAddressFrozen", "freezePartialTokens", "unfreezePartialTokens",
            "freezeToken", "unfreezeToken", "pause", "unpause", "pauseSlot", "unpauseSlot",
            // whitelist, identity, claims, trusted issuers, confidential viewers
            "whitelist", "removeFromWhitelist", "registerIdentity", "deleteIdentity", "addClaim", "removeClaim",
            "revokeClaimBySignature", "addClaimTopic", "addTrustedIssuer", "removeTrustedIssuer", "addViewer",
            "removeViewer",
            // compliance modules and their limits
            "addModule", "removeModule", "setMaxBalance", "setMaxInvestors", "setTransferCooldown", "blockCountry",
            "setNomineePool", "syncHolders",
            // supply caps, NAV, deposit caps, slot metadata
            "setSupplyCap", "setSlotSupplyCap", "setSlotMetadataHash", "setDepositCap", "setNavPerShare",
            // vault request lifecycle
            "fulfillDepositRequest", "fulfillRedeemRequest", "cancelDepositRequest", "cancelRedeemRequest",
            "forceCancelDepositRequest", "forceCancelRedeemRequest",
            // forward pricing: the dealing cut-off decides which NAV a vault request settles at (T1-07)
            "setDealingCutoff",
            // ownership and instrument / identity deployments
            "acceptOwnership", "deployEwpgSuite", "deployIdentityProxy", "deployConfidentialErc20",
            "deployConfidentialErc3643", "deployToken", "deployVault",
            // ecosystem: organisations, permissions, dApp registry
            "registerOrg", "suspendOrg", "reinstateOrg", "addMember", "removeMember", "setMemberRoles",
            "definePermission", "grantToOrg", "revokeFromOrg", "grantToRole", "revokeFromRole", "setRoleRestricted",
            "registerDapp", "updateManifest", "setStatus");

    /**
     * State-changing calls the backend signs that deliberately do <em>not</em> change the register: the lending
     * market's own risk controls and collateral bookkeeping. They are protective or mirror a register move that
     * is itself confirmed from a second source. Everything else that is not listed in {@link #MUTATING} is
     * treated as registry-mutating.
     */
    public static final Set<String> NOT_REGISTRY_MUTATING = lower("setBorrowPaused", "reconcileCollateral");

    private RegistryMutatingMethods() {
    }

    /**
     * @return {@code false} only for a name explicitly listed in {@link #NOT_REGISTRY_MUTATING}; {@code true}
     *         for every listed registry-mutating name and (fail closed) for any unknown or missing name
     */
    public static boolean requiresSecondSource(String methodName) {
        if (methodName == null || methodName.isBlank()) return true;
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (MUTATING.contains(lower)) return true;
        return !NOT_REGISTRY_MUTATING.contains(lower);
    }

    /** @return true when {@code methodName} is classified (either way), i.e. not subject to the fail-closed default */
    public static boolean isClassified(String methodName) {
        if (methodName == null) return false;
        String lower = methodName.toLowerCase(Locale.ROOT);
        return MUTATING.contains(lower) || NOT_REGISTRY_MUTATING.contains(lower);
    }

    private static Set<String> lower(String... names) {
        return java.util.Arrays.stream(names).map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
