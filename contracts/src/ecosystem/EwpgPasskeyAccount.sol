// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {Account} from "@openzeppelin/contracts/account/Account.sol";
import {ERC7821} from "@openzeppelin/contracts/account/extensions/draft-ERC7821.sol";
import {SignerWebAuthn} from "@openzeppelin/contracts/utils/cryptography/signers/SignerWebAuthn.sol";
import {AbstractSigner} from "@openzeppelin/contracts/utils/cryptography/signers/AbstractSigner.sol";
import {SignerP256} from "@openzeppelin/contracts/utils/cryptography/signers/SignerP256.sol";
import {IEntryPoint} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import {IERC1271} from "@openzeppelin/contracts/interfaces/IERC1271.sol";
import {Execution} from "@openzeppelin/contracts/interfaces/draft-IERC7579.sol";
import {ERC7579Utils} from "@openzeppelin/contracts/account/utils/draft-ERC7579Utils.sol";

/// @title EwpgPasskeyAccount
/// @notice Reference minimal ERC-4337 smart account secured by a passkey (WebAuthn/secp256r1)
///         signer instead of a seed-phrase-managed ECDSA key — the retail-onboarding UX
///         described in `docs/platform/account-abstraction.md`, deployed as a fresh ERC-4337
///         account for a customer who has never held a wallet before.
///
///         **NOT an EIP-7702 delegate.** Per-account state (the passkey `signer()` and the
///         `callRole` table) is constructor-initialised in *this* contract's storage, and
///         `guardian` is an immutable in its code. An EOA that delegates to a deployed instance
///         runs that code against its *own* (empty) storage — no passkey is set, so every
///         signature fails closed ({_rawSignatureValidation}) — while sharing the instance's
///         guardian with every other delegating EOA. Do not point a 7702 authorization at it —
///         and if one does, the guardian's privileged functions ({guardianExecute},
///         {setCallRole}) refuse to run in the delegating account's context ({NotInstance}), so the
///         shared guardian key cannot act as the guardian of every EOA that delegates to an instance.
///
///         **Guardian = full custodial override.** {guardianExecute} performs an arbitrary call
///         from the account with no timelock, role check or passkey co-signature; whoever holds
///         the guardian key controls the account's assets. The guardian is therefore an explicit
///         constructor argument (never the deployer by accident). Whether a registry-held
///         guardian with unilateral control is intended is an open custody decision.
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
    using ERC7579Utils for bytes;

    bytes32 public constant ROLE_ROUTINE = keccak256("ROUTINE");
    bytes32 public constant ROLE_ADMIN = keccak256("ADMIN");
    bytes32 public constant ROLE_RECOVERY = keccak256("RECOVERY");

    IEntryPoint private immutable _entryPoint;
    address public immutable guardian;
    /// @dev The instance's own address (immutables live in code, which a 7702 delegate shares).
    address private immutable _self = address(this);
    mapping(address => mapping(bytes4 => bytes32)) public callRole;

    event CallRoleSet(address indexed target, bytes4 indexed selector, bytes32 indexed role);
    event GuardianExecution(address indexed target, bytes4 indexed selector);

    error GuardianRequired(address target, bytes4 selector);
    error NotGuardian();
    /// @notice A guardian function was called in the context of an EIP-7702 delegating account
    ///         rather than at the deployed instance.
    error NotInstance();
    error SelfCallTrampolineForbidden();
    error ZeroGuardian();

    constructor(IEntryPoint entryPoint_, bytes32 qx, bytes32 qy, address guardian_) SignerP256(qx, qy) {
        if (guardian_ == address(0)) revert ZeroGuardian();
        _entryPoint = entryPoint_;
        guardian = guardian_;
    }

    /// @notice Classifies high-risk calls. The guardian configures policy and is the only
    /// executor for ADMIN/RECOVERY operations; passkey UserOperations remain routine-only.
    /// Note that the guardian sets this table itself, so it restricts the passkey, not the
    /// guardian — see {guardianExecute}.
    function setCallRole(address target, bytes4 selector, bytes32 role) external {
        if (msg.sender != guardian) revert NotGuardian();
        if (address(this) != _self) revert NotInstance();
        require(role == ROLE_ROUTINE || role == ROLE_ADMIN || role == ROLE_RECOVERY, "invalid role");
        callRole[target][selector] = role;
        emit CallRoleSet(target, selector, role);
    }

    /// @notice Arbitrary call from the account by the guardian — a full custodial override,
    ///         not a protective-only path (see the contract NatSpec).
    function guardianExecute(address target, uint256 value, bytes calldata data)
        external
        payable
        returns (bytes memory result)
    {
        if (msg.sender != guardian) revert NotGuardian();
        if (address(this) != _self) revert NotInstance();
        (bool ok, bytes memory returned) = target.call{value: value}(data);
        if (!ok) {
            assembly ("memory-safe") { revert(add(returned, 32), mload(returned)) }
        }
        emit GuardianExecution(target, _selector(data));
        return returned;
    }

    /// @inheritdoc Account
    function entryPoint() public view override returns (IEntryPoint) {
        return _entryPoint;
    }

    /// @inheritdoc IERC1271
    function isValidSignature(bytes32 hash, bytes calldata signature) external view override returns (bytes4) {
        return _rawSignatureValidation(hash, signature) ? IERC1271.isValidSignature.selector : bytes4(0xffffffff);
    }

    /// @dev Fails closed when no passkey is set — the state of any EOA that (wrongly) delegates
    ///      to this contract via EIP-7702, whose own storage holds no `signer()`.
    function _rawSignatureValidation(bytes32 hash, bytes calldata signature)
        internal
        view
        override(AbstractSigner, SignerWebAuthn)
        returns (bool)
    {
        (bytes32 qx, bytes32 qy) = signer();
        if (qx == bytes32(0) && qy == bytes32(0)) return false;
        return super._rawSignatureValidation(hash, signature);
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
        if (caller == address(entryPoint())) {
            _validateExecutions(executionData.decodeBatch());
            return true;
        }
        return super._erc7821AuthorizedExecutor(caller, mode, executionData);
    }

    function _validateExecutions(Execution[] calldata executions) private view {
        for (uint256 i = 0; i < executions.length; ++i) {
            _validateExecution(executions[i].target, executions[i].callData);
        }
    }

    /// @dev A self-`execute` call is rejected outright, whether or not it is itself wrapped in a
    ///      batch of one — attempting to decode and re-validate its nested executions here would
    ///      just change which error surfaces (`GuardianRequired` instead of this one) without
    ///      changing the outcome, since the call is refused either way. The unconditional revert
    ///      below is the actual guard; there is nothing to gain from walking into the trampoline
    ///      before closing it.
    function _validateExecution(address target, bytes calldata data) private view {
        address resolvedTarget = target == address(0) ? address(this) : target;
        bytes4 selector = _selector(data);

        if (resolvedTarget == address(this) && selector == this.execute.selector) {
            revert SelfCallTrampolineForbidden();
        }

        bytes32 required = callRole[resolvedTarget][selector];
        if (required == ROLE_ADMIN || required == ROLE_RECOVERY) {
            revert GuardianRequired(resolvedTarget, selector);
        }
    }

    function _selector(bytes calldata data) private pure returns (bytes4) {
        return data.length < 4 ? bytes4(0) : bytes4(data[:4]);
    }
}
