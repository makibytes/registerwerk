// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {Account} from "@openzeppelin/contracts/account/Account.sol";
import {ERC7821} from "@openzeppelin/contracts/account/extensions/draft-ERC7821.sol";
import {SignerWebAuthn} from "@openzeppelin/contracts/utils/cryptography/signers/SignerWebAuthn.sol";
import {SignerP256} from "@openzeppelin/contracts/utils/cryptography/signers/SignerP256.sol";
import {IEntryPoint} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import {IERC1271} from "@openzeppelin/contracts/interfaces/IERC1271.sol";
import {Execution} from "@openzeppelin/contracts/interfaces/draft-IERC7579.sol";
import {ERC7579Utils} from "@openzeppelin/contracts/account/utils/draft-ERC7579Utils.sol";

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
    using ERC7579Utils for bytes;

    bytes32 public constant ROLE_ROUTINE = keccak256("ROUTINE");
    bytes32 public constant ROLE_ADMIN = keccak256("ADMIN");
    bytes32 public constant ROLE_RECOVERY = keccak256("RECOVERY");

    IEntryPoint private immutable _entryPoint;
    address public immutable guardian;
    mapping(address => mapping(bytes4 => bytes32)) public callRole;

    event CallRoleSet(address indexed target, bytes4 indexed selector, bytes32 indexed role);
    event GuardianExecution(address indexed target, bytes4 indexed selector);

    error GuardianRequired(address target, bytes4 selector);
    error NotGuardian();
    error SelfCallTrampolineForbidden();

    constructor(IEntryPoint entryPoint_, bytes32 qx, bytes32 qy) SignerP256(qx, qy) {
        _entryPoint = entryPoint_;
        guardian = msg.sender;
    }

    /// @notice Classifies high-risk calls. The HSM-backed guardian configures policy and is the
    /// only executor for ADMIN/RECOVERY operations; passkey UserOperations remain routine-only.
    function setCallRole(address target, bytes4 selector, bytes32 role) external {
        if (msg.sender != guardian) revert NotGuardian();
        require(role == ROLE_ROUTINE || role == ROLE_ADMIN || role == ROLE_RECOVERY, "invalid role");
        callRole[target][selector] = role;
        emit CallRoleSet(target, selector, role);
    }

    function guardianExecute(address target, uint256 value, bytes calldata data)
        external
        payable
        returns (bytes memory result)
    {
        if (msg.sender != guardian) revert NotGuardian();
        (bool ok, bytes memory returned) = target.call{value: value}(data);
        if (!ok) assembly ("memory-safe") { revert(add(returned, 32), mload(returned)) }
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

    function _validateExecution(address target, bytes calldata data) private view {
        address resolvedTarget = target == address(0) ? address(this) : target;
        bytes4 selector = _selector(data);

        if (resolvedTarget == address(this) && selector == this.execute.selector) {
            if (data.length > 4) {
                (, bytes memory nestedRaw) = abi.decode(data[4:], (bytes32, bytes));
                _validateNestedExecutions(abi.decode(nestedRaw, (Execution[])));
            }
            revert SelfCallTrampolineForbidden();
        }

        bytes32 required = callRole[resolvedTarget][selector];
        if (required == ROLE_ADMIN || required == ROLE_RECOVERY) {
            revert GuardianRequired(resolvedTarget, selector);
        }
    }

    function _validateNestedExecutions(Execution[] memory executions) private view {
        for (uint256 i = 0; i < executions.length; ++i) {
            address resolvedTarget = executions[i].target == address(0) ? address(this) : executions[i].target;
            bytes memory callData = executions[i].callData;
            bytes4 selector = _selectorMemory(callData);

            if (resolvedTarget == address(this) && selector == this.execute.selector) {
                if (callData.length > 4) {
                    _validateNestedExecutions(_decodeNestedExecute(callData));
                }
                revert SelfCallTrampolineForbidden();
            }

            bytes32 required = callRole[resolvedTarget][selector];
            if (required == ROLE_ADMIN || required == ROLE_RECOVERY) {
                revert GuardianRequired(resolvedTarget, selector);
            }
        }
    }

    function _selector(bytes calldata data) private pure returns (bytes4) {
        return data.length < 4 ? bytes4(0) : bytes4(data[:4]);
    }

    function _selectorMemory(bytes memory data) private pure returns (bytes4 selector) {
        if (data.length < 4) {
            return bytes4(0);
        }
        assembly ("memory-safe") {
            selector := shr(224, mload(add(data, 32)))
        }
    }

    function _decodeNestedExecute(bytes memory callData) private pure returns (Execution[] memory) {
        bytes memory args = new bytes(callData.length - 4);
        for (uint256 i = 4; i < callData.length; ++i) {
            args[i - 4] = callData[i];
        }
        (, bytes memory nestedRaw) = abi.decode(args, (bytes32, bytes));
        return abi.decode(nestedRaw, (Execution[]));
    }
}
