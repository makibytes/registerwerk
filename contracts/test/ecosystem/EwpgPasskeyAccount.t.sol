// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import {IEntryPoint, PackedUserOperation} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import {IERC1271} from "@openzeppelin/contracts/interfaces/IERC1271.sol";
import {Account as ERC4337Account} from "@openzeppelin/contracts/account/Account.sol";
import {Base64} from "@openzeppelin/contracts/utils/Base64.sol";
import {P256} from "@openzeppelin/contracts/utils/cryptography/P256.sol";
import {Execution} from "@openzeppelin/contracts/interfaces/draft-IERC7579.sol";
import "../../src/ecosystem/EwpgPasskeyAccount.sol";
import "./mocks/MockEntryPoint.sol";

/// @notice Builds real WebAuthn authentication-assertion vectors using Foundry's native P256
///         cheatcodes (`vm.publicKeyP256`/`vm.signP256`) rather than a fixture recorded from an
///         actual browser/authenticator — the cryptography exercised is identical either way.
contract EwpgPasskeyAccountTest is Test {
    EwpgPasskeyAccount account;
    MockEntryPoint entryPoint;
    uint256 constant PRIVATE_KEY = 0xA11CE;
    bytes32 qx;
    bytes32 qy;

    string constant HEADER_BEFORE_CHALLENGE = '{"type":"webauthn.get",';

    function setUp() public {
        (uint256 x, uint256 y) = vm.publicKeyP256(PRIVATE_KEY);
        (qx, qy) = (bytes32(x), bytes32(y));
        entryPoint = new MockEntryPoint();
        account = new EwpgPasskeyAccount(IEntryPoint(address(entryPoint)), qx, qy);
    }

    function _sign(bytes32 hash, uint256 signingKey) private pure returns (bytes memory) {
        uint256 challengeIndex = bytes(HEADER_BEFORE_CHALLENGE).length;
        string memory clientDataJSON = string.concat(
            HEADER_BEFORE_CHALLENGE,
            '"challenge":"',
            Base64.encodeURL(abi.encodePacked(hash)),
            '","origin":"https://passkeys.registerwerk.example"}'
        );
        bytes memory authenticatorData = abi.encodePacked(
            keccak256("rpIdHash-demo"), // 32-byte stand-in — WebAuthn.verify doesn't check it (see its NatSpec)
            bytes1(0x05), // flags: User Present + User Verified
            uint32(0) // signature counter
        );
        bytes32 messageHash = sha256(abi.encodePacked(authenticatorData, sha256(bytes(clientDataJSON))));
        (bytes32 r, bytes32 s) = vm.signP256(signingKey, messageHash);
        // P256.verify only accepts the canonical low-S form (malleability protection) —
        // vm.signP256 doesn't guarantee it, so normalize here exactly as a real WebAuthn
        // client library would.
        if (uint256(s) > P256.N / 2) {
            s = bytes32(P256.N - uint256(s));
        }

        // NOTE: plain `abi.encode(auth)` would add an extra leading offset word (`auth`'s
        // dynamic struct type gets treated as a single dynamic "argument" to abi.encode),
        // which WebAuthn.tryDecodeAuth does not expect — it wants the raw 6-field tuple
        // encoding, produced by encoding the fields as separate arguments instead.
        return abi.encode(r, s, challengeIndex, uint256(1), authenticatorData, clientDataJSON);
    }

    // ── ERC-1271 (binds as a Registerwerk member wallet) ────────────────────

    function test_isValidSignature_acceptsValidPasskeyAssertion() public view {
        bytes32 hash = keccak256("wallet binding challenge digest");
        bytes memory signature = _sign(hash, PRIVATE_KEY);
        assertEq(account.isValidSignature(hash, signature), IERC1271.isValidSignature.selector);
    }

    function test_isValidSignature_rejectsAssertionFromWrongKey() public view {
        bytes32 hash = keccak256("wallet binding challenge digest");
        bytes memory signature = _sign(hash, 0xB0B); // different passkey than the account's registered one
        assertEq(account.isValidSignature(hash, signature), bytes4(0xffffffff));
    }

    function test_isValidSignature_rejectsAssertionOverWrongHash() public view {
        bytes memory signature = _sign(keccak256("original digest"), PRIVATE_KEY);
        assertEq(account.isValidSignature(keccak256("different digest"), signature), bytes4(0xffffffff));
    }

    // ── ERC-4337 validateUserOp ──────────────────────────────────────────────

    function _buildUserOp(bytes memory signature) private view returns (PackedUserOperation memory) {
        return PackedUserOperation({
            sender: address(account),
            nonce: 0,
            initCode: "",
            callData: "",
            accountGasLimits: bytes32(0),
            preVerificationGas: 0,
            gasFees: bytes32(0),
            paymasterAndData: "",
            signature: signature
        });
    }

    function test_validateUserOp_succeedsForValidPasskeySignature() public {
        bytes32 userOpHash = keccak256("userOp-1");
        PackedUserOperation memory userOp = _buildUserOp(_sign(userOpHash, PRIVATE_KEY));

        vm.prank(address(entryPoint));
        uint256 validationData = account.validateUserOp(userOp, userOpHash, 0);
        assertEq(validationData, 0, "SIG_VALIDATION_SUCCESS");
    }

    function test_validateUserOp_returnsFailureForWrongSigner() public {
        bytes32 userOpHash = keccak256("userOp-1");
        PackedUserOperation memory userOp = _buildUserOp(_sign(userOpHash, 0xB0B));

        vm.prank(address(entryPoint));
        uint256 validationData = account.validateUserOp(userOp, userOpHash, 0);
        assertEq(validationData, 1, "SIG_VALIDATION_FAILED");
    }

    function test_validateUserOp_revertsForNonEntryPointCaller() public {
        bytes32 userOpHash = keccak256("userOp-1");
        PackedUserOperation memory userOp = _buildUserOp(_sign(userOpHash, PRIVATE_KEY));

        vm.expectRevert(abi.encodeWithSelector(ERC4337Account.AccountUnauthorized.selector, address(this)));
        account.validateUserOp(userOp, userOpHash, 0);
    }

    function test_entryPoint_returnsConstructorValue() public view {
        assertEq(address(account.entryPoint()), address(entryPoint));
    }

    function test_entryPointCannotExecuteAdminClassifiedCall() public {
        bytes4 selector = bytes4(keccak256("pause()"));
        account.setCallRole(address(0xB0B), selector, account.ROLE_ADMIN());
        Execution[] memory calls = new Execution[](1);
        calls[0] = Execution({target: address(0xB0B), value: 0, callData: abi.encodeWithSelector(selector)});

        vm.prank(address(entryPoint));
        vm.expectRevert(abi.encodeWithSelector(EwpgPasskeyAccount.GuardianRequired.selector, address(0xB0B), selector));
        account.execute(bytes32(uint256(1) << 248), abi.encode(calls));
    }


    function test_entryPointRejectsNestedExecuteSelfCallTrampoline() public {
        bytes4 selector = bytes4(keccak256("pause()"));
        account.setCallRole(address(0xB0B), selector, account.ROLE_ADMIN());

        Execution[] memory nested = new Execution[](1);
        nested[0] = Execution({target: address(0xB0B), value: 0, callData: abi.encodeWithSelector(selector)});

        Execution[] memory outer = new Execution[](1);
        outer[0] = Execution({
            target: address(0),
            value: 0,
            callData: abi.encodeWithSelector(account.execute.selector, bytes32(uint256(1) << 248), abi.encode(nested))
        });

        vm.prank(address(entryPoint));
        vm.expectRevert(EwpgPasskeyAccount.SelfCallTrampolineForbidden.selector);
        account.execute(bytes32(uint256(1) << 248), abi.encode(outer));
    }

    function test_onlyGuardianCanConfigureHighRiskPolicy() public {
        bytes32 adminRole = account.ROLE_ADMIN();
        vm.expectRevert(EwpgPasskeyAccount.NotGuardian.selector);
        vm.prank(address(0xBAD));
        account.setCallRole(address(0xB0B), bytes4(keccak256("pause()")), adminRole);
    }
}
