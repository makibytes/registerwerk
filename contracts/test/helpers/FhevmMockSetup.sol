// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import {euint64, externalEuint64} from "@fhevm/solidity/lib/FHE.sol";
import "../../src/confidential/ConfidentialERC20.sol";
import {MockACL, MockFhevmExecutor, MockKmsVerifier} from "../mocks/MockFhevm.sol";

/// @notice Deploys the cleartext FHEVM mocks and offers helpers to feed encrypted inputs to a
///         confidential token and to read back the cleartext behind a handle. See {MockFhevm.sol}
///         for what the mocks do and do not model.
abstract contract FhevmMockSetup is Test {
    uint256 internal constant KMS_SIGNER_KEY = 0xA11CE;

    MockACL internal acl;
    MockFhevmExecutor internal executor;
    MockKmsVerifier internal kms;
    uint256 private _inputNonce;

    function _deployFhevmMocks() internal returns (ConfidentialERC20.FhevmInfra memory infra) {
        acl = new MockACL();
        executor = new MockFhevmExecutor(acl);
        kms = new MockKmsVerifier(vm.addr(KMS_SIGNER_KEY));
        infra = ConfidentialERC20.FhevmInfra({
            aclAddress: address(acl),
            coprocessorAddress: address(executor),
            kmsVerifierAddress: address(kms)
        });
    }

    /// @dev A fresh encrypted input holding `value`: the (mock) input proof is its ABI-encoded cleartext.
    function _input(uint256 value) internal returns (externalEuint64 handle, bytes memory proof) {
        handle = externalEuint64.wrap(keccak256(abi.encode("registerwerk-test-input", ++_inputNonce)));
        proof = abi.encode(value);
    }

    function _clear(euint64 handle) internal view returns (uint256) {
        return executor.clear(euint64.unwrap(handle));
    }

    function _persistAllowed(euint64 handle, address who) internal view returns (bool) {
        return acl.persistAllowed(euint64.unwrap(handle), who);
    }

    /// @dev A KMS public-decryption proof for `(handle, value)` signed by the mock KMS signer.
    function _decryptionProof(euint64 handle, uint64 value)
        internal
        view
        returns (bytes memory cleartexts, bytes memory proof)
    {
        bytes32[] memory handles = new bytes32[](1);
        handles[0] = euint64.unwrap(handle);
        cleartexts = abi.encode(value);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(KMS_SIGNER_KEY, kms.digestOf(handles, cleartexts));
        proof = abi.encodePacked(uint8(1), r, s, v);
    }
}
