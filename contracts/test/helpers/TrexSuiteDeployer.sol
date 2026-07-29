// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@erc3643/proxy/authority/TREXImplementationAuthority.sol";
import "@erc3643/proxy/authority/ITREXImplementationAuthority.sol";
import "@erc3643/registry/implementation/ClaimTopicsRegistry.sol";
import "@erc3643/registry/implementation/IdentityRegistry.sol";
import "@erc3643/registry/implementation/IdentityRegistryStorage.sol";
import "@erc3643/registry/implementation/TrustedIssuersRegistry.sol";
import "@erc3643/compliance/modular/ModularCompliance.sol";
import "@onchain-id/solidity/contracts/Identity.sol";
import "@onchain-id/solidity/contracts/proxy/ImplementationAuthority.sol";
import "@onchain-id/solidity/contracts/factory/IdFactory.sol";
import "../../src/factory/EwpgTREXFactory.sol";
import "../../src/tokens/EwpgERC3643.sol";

/// @notice Bundle of infrastructure handles returned by {TrexSuiteDeployer.deploy}.
struct TrexDeployment {
    /// @notice The eWpG-aware T-REX suite factory — call `deployEwpgSuite` on this.
    EwpgTREXFactory factory;
    /// @notice ONCHAINID identity factory — mints investor/token ONCHAINIDs.
    IdFactory idFactory;
    /// @notice ONCHAINID implementation authority backing every identity proxy.
    ImplementationAuthority onchainIdAuthority;
    /// @notice T-REX implementation authority backing every token/registry proxy.
    TREXImplementationAuthority trexAuthority;
}

/// @title TrexSuiteDeployer
/// @notice Test/script helper that performs the one-time T-REX + ONCHAINID "bring-up":
///         deploying the six T-REX implementation (logic) contracts, registering them on
///         a fresh {TREXImplementationAuthority}, deploying the ONCHAINID identity
///         factory, and wiring an {EwpgTREXFactory} on top so `deployEwpgSuite` can mint
///         real, fully-functional EwpgERC3643 bond suites.
///
/// @dev No production code depends on this contract — it exists purely so tests and
///      deployment scripts don't have to repeat the ~10-step T-REX bootstrap sequence
///      documented in {EwpgTREXFactory} and the T-REX `TREXFactory`/
///      `TREXImplementationAuthority` NatSpec. This is a `library` with only `internal`
///      functions: Solidity inlines internal library calls into the caller's own
///      bytecode (no separate `DELEGATECALL`/`CALL` frame), so every `new` performed
///      here runs with `msg.sender == <the calling contract>` — the same as if the
///      caller had written this deployment sequence inline. `deploy` still finishes by
///      explicitly transferring ownership of every `Ownable` piece of infrastructure to
///      `owner_`, so callers get a deterministic result regardless of who happened to be
///      `msg.sender` during the intermediate steps.
library TrexSuiteDeployer {
    /// @notice Deploys a complete, empty T-REX + ONCHAINID environment and hands
    ///         ownership of the reusable infrastructure (the T-REX implementation
    ///         authority, the ONCHAINID factory, and the eWpG suite factory) to `owner_`.
    /// @param owner_ The address that should control the deployment infrastructure
    ///        afterwards (e.g. an `operator` test address) — typically the same address
    ///        that will later call `factory.deployEwpgSuite(...)` and
    ///        `idFactory.createIdentity(...)`.
    function deploy(address owner_) internal returns (TrexDeployment memory dep) {
        require(owner_ != address(0), "TrexSuiteDeployer: zero owner");

        // ── 1. T-REX implementation (logic) contracts ────────────────────────
        // EwpgERC3643 (not the bare T-REX `Token`) is registered as the token logic so
        // every proxy the factory creates exposes `setAssetId` — see its NatSpec.
        EwpgERC3643 tokenImpl = new EwpgERC3643();
        ClaimTopicsRegistry ctrImpl = new ClaimTopicsRegistry();
        IdentityRegistry irImpl = new IdentityRegistry();
        IdentityRegistryStorage irsImpl = new IdentityRegistryStorage();
        TrustedIssuersRegistry tirImpl = new TrustedIssuersRegistry();
        ModularCompliance mcImpl = new ModularCompliance();

        TREXImplementationAuthority trexAuthority =
            new TREXImplementationAuthority(true, address(0), address(0));
        trexAuthority.addAndUseTREXVersion(
            ITREXImplementationAuthority.Version({major: 4, minor: 0, patch: 0}),
            ITREXImplementationAuthority.TREXContracts({
                tokenImplementation: address(tokenImpl),
                ctrImplementation: address(ctrImpl),
                irImplementation: address(irImpl),
                irsImplementation: address(irsImpl),
                tirImplementation: address(tirImpl),
                mcImplementation: address(mcImpl)
            })
        );

        // ── 2. ONCHAINID infrastructure (investor + token identities) ────────
        // `initialManagementKey` is required to be non-zero but is otherwise unused for
        // a library implementation contract (`_isLibrary = true` skips key setup).
        Identity identityImpl = new Identity(address(0xdEaD), true);
        ImplementationAuthority onchainIdAuthority = new ImplementationAuthority(address(identityImpl));
        IdFactory idFactory = new IdFactory(address(onchainIdAuthority));

        // ── 3. The eWpG-aware T-REX factory ───────────────────────────────────
        // EwpgTREXFactory deploys and owns an inner TREXFactory in its own constructor.
        EwpgTREXFactory factory = new EwpgTREXFactory(address(trexAuthority), address(idFactory));
        trexAuthority.setTREXFactory(address(factory.trexFactory()));
        idFactory.addTokenFactory(address(factory.trexFactory()));

        // ── 4. Hand control to the caller-designated owner ───────────────────
        trexAuthority.transferOwnership(owner_);
        idFactory.transferOwnership(owner_);
        factory.transferOwnership(owner_);

        dep.factory = factory;
        dep.idFactory = idFactory;
        dep.onchainIdAuthority = onchainIdAuthority;
        dep.trexAuthority = trexAuthority;
    }
}
