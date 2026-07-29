// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "@erc3643/token/Token.sol";
import "@erc3643/token/IToken.sol";
import {ITREXImplementationAuthority} from "@erc3643/proxy/authority/ITREXImplementationAuthority.sol";

/// @dev Minimal Ownable view used to resolve the owner of the inner TREXFactory.
interface IOwnableView {
    function owner() external view returns (address);
}

/**
 * @title EwpgERC3643
 * @notice ERC-3643 (T-REX) security-token reference with an eWpG-oriented registry link.
 *         The token standard and link do not establish legal-register compliance.
 *         Deployed via the T-REX Factory — do not deploy directly.
 *         Wraps the T-REX Token contract with eWpG registry linkage.
 *
 * Key features inherited from T-REX Token:
 *  - Transfer compliance enforced via Identity Registry + Compliance contract
 *  - Freeze/pause per address or globally
 *  - Force transfer (agent-only, for regulatory recovery)
 *  - Batch mint/burn/transfer
 *
 * @dev The assetId links this contract back to the registry DB. This single
 *      EwpgERC3643 instance is deployed once and registered as the shared T-REX
 *      *implementation* (logic) contract behind every token proxy {EwpgTREXFactory}
 *      creates (see {TokenProxy}: proxies delegatecall into this contract's `init`
 *      and every other function, but never run its constructor). Storing `assetId`
 *      as `immutable` would therefore be a bug — immutables are inlined directly into
 *      the logic contract's own bytecode, so a `DELEGATECALL`-based read would return
 *      the *implementation* contract's value identically for every proxy sharing it,
 *      not the individual proxy's own asset. `assetId` is instead ordinary storage,
 *      set once per proxy via {setAssetId}.
 */
contract EwpgERC3643 is Token {
    /// @notice Registry DB asset ID (bytes32 keccak of UUID) this proxy is linked to.
    bytes32 public assetId;

    event EwpgAssetLinked(bytes32 indexed assetId);

    /// @notice Emitted after a registry-initiated forced approval override (see {forcedApprove}).
    /// @dev Deliberately re-declared here rather than imported from
    ///      {IEwpgAdminControls} (which every other Ewpg token contract implements via
    ///      {EwpgCompliance}): that interface is pragma ^0.8.36, while this contract
    ///      transitively pins to exactly solc 0.8.30 via T-REX's `Token.sol`
    ///      (`pragma solidity 0.8.30;`) — an unsatisfiable combination if imported.
    ///      The event signature (and therefore its topic hash / ABI decoding) is
    ///      identical either way, so off-chain indexers and the backend decode it
    ///      the same regardless of which file declares it.
    event ForcedApprove(address indexed owner, address indexed spender, uint256 value, string legalBasis);

    error AssetIdAlreadySet();
    error ZeroAssetId();
    error UnauthorizedAssetLinker(address caller);

    /// @notice One-time initializer binding this proxy to a registry DB asset ID.
    ///         Called by {EwpgTREXFactory.deployEwpgSuite} in the same transaction that
    ///         deploys this proxy.
    /// @dev Restricted to the owner of the T-REX factory registered on this proxy's
    ///      ImplementationAuthority — i.e. the {EwpgTREXFactory} wrapper that owns the
    ///      inner TREXFactory and performs the same-tx linkage. The one-time guard alone
    ///      is not sufficient: any proxy created without an immediate setAssetId call
    ///      would otherwise let an arbitrary caller bind a foreign assetId and corrupt
    ///      the registry↔chain linkage. Reads the proxy's IA slot directly (this code
    ///      runs via DELEGATECALL in the proxy's storage context); on the bare logic
    ///      contract the slot is zero and the call reverts, which is intended.
    function setAssetId(bytes32 _assetId) external {
        if (assetId != bytes32(0)) revert AssetIdAlreadySet();
        if (_assetId == bytes32(0)) revert ZeroAssetId();
        address implementationAuthority;
        // keccak256("ERC-3643.proxy.beacon") — same slot AbstractProxy uses.
        assembly {
            implementationAuthority := sload(0x821f3e4d3d679f19eacc940c87acf846ea6eae24a63058ea750304437a62aafc)
        }
        if (implementationAuthority == address(0)) revert UnauthorizedAssetLinker(msg.sender);
        address trexFactory = ITREXImplementationAuthority(implementationAuthority).getTREXFactory();
        if (trexFactory == address(0) || msg.sender != IOwnableView(trexFactory).owner()) {
            revert UnauthorizedAssetLinker(msg.sender);
        }
        assetId = _assetId;
        emit EwpgAssetLinked(_assetId);
    }

    /// @notice Registry-compulsory allowance override (eWpG §24-style correction).
    ///         T-REX has no native forcedApprove; this mirrors the registry's existing
    ///         `forcedTransfer` authority (also `onlyAgent`, see T-REX `Token.forcedTransfer`)
    ///         so that agent-side control over a T-REX token is on par with every other
    ///         Ewpg token standard (EwpgERC20/721/1155/3525/4626 all already implement
    ///         `IEwpgAdminControls.forcedApprove`).
    /// @param owner      Token owner whose allowance is being overridden.
    /// @param spender    Spender receiving the allowance.
    /// @param amount     New allowance amount.
    /// @param legalBasis Reference to the legal authority (e.g. "BaFin order 2026-01").
    function forcedApprove(address owner, address spender, uint256 amount, string calldata legalBasis)
        external
        onlyAgent
    {
        _approve(owner, spender, amount);
        emit ForcedApprove(owner, spender, amount, legalBasis);
    }
}
