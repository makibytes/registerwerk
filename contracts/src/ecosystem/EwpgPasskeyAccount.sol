// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {Account} from "@openzeppelin/contracts/account/Account.sol";
import {ERC7821} from "@openzeppelin/contracts/account/extensions/draft-ERC7821.sol";
import {SignerWebAuthn} from "@openzeppelin/contracts/utils/cryptography/signers/SignerWebAuthn.sol";
import {SignerP256} from "@openzeppelin/contracts/utils/cryptography/signers/SignerP256.sol";
import {IEntryPoint} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import {IERC1271} from "@openzeppelin/contracts/interfaces/IERC1271.sol";

/// @title EwpgPasskeyAccount
/// @notice Reference minimal ERC-4337 smart account secured by a passkey (WebAuthn/secp256r1)
///         signer instead of a seed-phrase-managed ECDSA key — the retail-onboarding UX
///         described in `docs/platform/account-abstraction.md`. Intended either as the code
///         an EIP-7702 delegation designation points an existing EOA at (same address, new
///         signing logic — see the EIP-7702 section of that doc) or as a freshly-deployed
///         ERC-4337 account for a customer who has never held a wallet before.
///
/// @dev Composes three pieces already vendored via `contracts/lib/openzeppelin-contracts` but
///      unused elsewhere in this repo before this contract — no new dependency was added:
///        - `Account` — the ERC-4337 `validateUserOp` plumbing.
///        - `SignerWebAuthn` — validates a WebAuthn authentication assertion (a passkey
///          signature) against a stored secp256r1 public key.
///        - `ERC7821` — a minimal batch-execution interface, so the account can actually
///          call out to Registerwerk dApps once a UserOperation is validated.
///      Also implements ERC-1271 `isValidSignature` over the same passkey, so this account
///      can bind as a Registerwerk member wallet via `WalletSignatureVerifier`
///      (`orgidentity/api/WalletSignatureVerifier.java`, backend) exactly like any other
///      smart-contract wallet — no special-casing needed there.
///
///      Sponsorship: a `paymaster` (e.g. `EwpgPaymaster`) covers this account's gas the same
///      way it would for any other ERC-4337 sender — see that contract's NatSpec for why
///      `userOp.sender` (this account's address) is what gets checked against
///      `PermissionOracle`, independent of which signer scheme the account uses internally.
contract EwpgPasskeyAccount is Account, SignerWebAuthn, ERC7821, IERC1271 {
    IEntryPoint private immutable _entryPoint;

    constructor(IEntryPoint entryPoint_, bytes32 qx, bytes32 qy) SignerP256(qx, qy) {
        _entryPoint = entryPoint_;
    }

    /// @inheritdoc Account
    function entryPoint() public view override returns (IEntryPoint) {
        return _entryPoint;
    }

    /// @inheritdoc IERC1271
    function isValidSignature(bytes32 hash, bytes calldata signature) external view override returns (bytes4) {
        return _rawSignatureValidation(hash, signature) ? IERC1271.isValidSignature.selector : bytes4(0xffffffff);
    }

    /// @dev Allows the EntryPoint to drive {execute} (per a validated UserOperation), in
    ///      addition to the account calling itself — the standard ERC-7821 wiring for an
    ///      ERC-4337 account (see the doc comment on {ERC7821._erc7821AuthorizedExecutor}).
    function _erc7821AuthorizedExecutor(address caller, bytes32 mode, bytes calldata executionData)
        internal
        view
        override
        returns (bool)
    {
        return caller == address(entryPoint()) || super._erc7821AuthorizedExecutor(caller, mode, executionData);
    }
}
