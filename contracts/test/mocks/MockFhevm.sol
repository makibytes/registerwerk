// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {FheType} from "@fhevm/solidity/lib/FheType.sol";

/// @notice A cleartext stand-in for the FHEVM host stack (ACL, FHEVMExecutor, KMSVerifier), so the
///         confidential tokens can be exercised in an ordinary Foundry test instead of self-skipping
///         unless run against a real fhEVM network.
///
/// @dev The tokens take the three host addresses as constructor arguments (see
///      {ConfidentialERC20.FhevmInfra}), so these mocks are simply deployed and injected — nothing
///      is etched at a canonical address. They model what the tests need to assert:
///        - handles carry a cleartext value (`MockFhevmExecutor.clear`), so balance, allowance and
///          the silent-failure semantics of a transfer can be checked exactly;
///        - the ACL separates the *transient* grant a freshly computed handle gets from the
///          *persistent* grants (`persistAllowed`) a contract must make explicitly — exactly the
///          distinction the viewer-ACL code in the tokens exists for;
///        - the KMS verifier accepts a decryption proof only if it carries a valid ECDSA signature by
///          the mock signer over `(handles, cleartexts)`.
///      They do not model gas, HCU limits, input-proof binding or the EIP-712 form of the KMS digest.
contract MockACL {
    mapping(bytes32 => mapping(address => bool)) private _persistent;
    mapping(bytes32 => mapping(address => bool)) private _transient;
    mapping(bytes32 => bool) private _publiclyDecryptable;

    error SenderNotAllowed(bytes32 handle, address sender);

    event Allowed(address indexed caller, address indexed account, bytes32 handle);

    function allowTransient(bytes32 handle, address account) external {
        _transient[handle][account] = true;
    }

    function allow(bytes32 handle, address account) external {
        if (!isAllowed(handle, msg.sender)) revert SenderNotAllowed(handle, msg.sender);
        _persistent[handle][account] = true;
        emit Allowed(msg.sender, account, handle);
    }

    function isAllowed(bytes32 handle, address account) public view returns (bool) {
        return _persistent[handle][account] || _transient[handle][account];
    }

    /// @dev Only the persistent grants: what survives the transaction.
    function persistAllowed(bytes32 handle, address account) external view returns (bool) {
        return _persistent[handle][account];
    }

    function allowForDecryption(bytes32[] memory handlesList) external {
        for (uint256 i = 0; i < handlesList.length; i++) {
            if (!isAllowed(handlesList[i], msg.sender)) revert SenderNotAllowed(handlesList[i], msg.sender);
            _publiclyDecryptable[handlesList[i]] = true;
        }
    }

    function isAllowedForDecryption(bytes32 handle) external view returns (bool) {
        return _publiclyDecryptable[handle];
    }

    function cleanTransientStorage() external {}
}

contract MockFhevmExecutor {
    MockACL public immutable acl;
    uint256 private _nonce;

    /// @notice handle => cleartext value (euint64 results wrap modulo 2^64, as the real type does).
    mapping(bytes32 => uint256) public clear;

    uint256 private constant MASK64 = type(uint64).max;

    constructor(MockACL acl_) {
        acl = acl_;
    }

    function _result(uint256 value) private returns (bytes32 handle) {
        handle = keccak256(abi.encode(address(this), ++_nonce));
        clear[handle] = value;
        // A freshly computed handle is usable by the contract that asked for it, in this transaction.
        acl.allowTransient(handle, msg.sender);
    }

    function _operand(bytes32 value, bytes1 scalarByte) private view returns (uint256) {
        return scalarByte == 0x01 ? uint256(value) : clear[value];
    }

    function trivialEncrypt(uint256 ct, FheType) external returns (bytes32) {
        return _result(ct & MASK64);
    }

    /// @dev The mock "input proof" is just `abi.encode(uint256 cleartext)`.
    function verifyInput(bytes32 inputHandle, address, bytes memory inputProof, FheType) external returns (bytes32) {
        clear[inputHandle] = abi.decode(inputProof, (uint256)) & MASK64;
        acl.allowTransient(inputHandle, msg.sender);
        return inputHandle;
    }

    function fheAdd(bytes32 lhs, bytes32 rhs, bytes1 scalarByte) external returns (bytes32) {
        return _result((clear[lhs] + _operand(rhs, scalarByte)) & MASK64);
    }

    function fheSub(bytes32 lhs, bytes32 rhs, bytes1 scalarByte) external returns (bytes32) {
        return _result((clear[lhs] - _operand(rhs, scalarByte)) & MASK64);
    }

    function fheLe(bytes32 lhs, bytes32 rhs, bytes1 scalarByte) external returns (bytes32) {
        return _result(clear[lhs] <= _operand(rhs, scalarByte) ? 1 : 0);
    }

    function fheIfThenElse(bytes32 control, bytes32 ifTrue, bytes32 ifFalse) external returns (bytes32) {
        return _result(clear[control] == 1 ? clear[ifTrue] : clear[ifFalse]);
    }

    function cast(bytes32 ct, FheType) external returns (bytes32) {
        return _result(clear[ct]);
    }

    function checkHandleType(bytes32, FheType) external view {}
}

contract MockKmsVerifier {
    address public immutable signer;

    constructor(address signer_) {
        signer = signer_;
    }

    /// @dev Proof layout as in production: `numSigners(1) ‖ signature(65) ‖ extraData`. The signed digest is
    ///      `keccak256(abi.encode(handles, cleartexts))` in this mock.
    function verifyDecryptionEIP712KMSSignatures(
        bytes32[] memory handlesList,
        bytes memory decryptedResult,
        bytes memory decryptionProof
    ) external view returns (bool) {
        if (decryptionProof.length < 66 || uint8(decryptionProof[0]) != 1) return false;
        bytes memory sig = new bytes(65);
        for (uint256 i = 0; i < 65; i++) {
            sig[i] = decryptionProof[1 + i];
        }
        bytes32 digest = digestOf(handlesList, decryptedResult);
        bytes32 r;
        bytes32 s;
        uint8 v;
        assembly ("memory-safe") {
            r := mload(add(sig, 0x20))
            s := mload(add(sig, 0x40))
            v := byte(0, mload(add(sig, 0x60)))
        }
        return ecrecover(digest, v, r, s) == signer;
    }

    function digestOf(bytes32[] memory handlesList, bytes memory decryptedResult) public pure returns (bytes32) {
        return keccak256(abi.encode(handlesList, decryptedResult));
    }
}
