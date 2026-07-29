// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import {PackedUserOperation, IEntryPoint, IPaymaster} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/EwpgPaymaster.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "./mocks/MockClaimIssuer.sol";
import "./mocks/MockEntryPoint.sol";
import "./mocks/MockOnchainId.sol";

/// @notice {EwpgPaymaster.validatePaymasterUserOp}/{postOp} are exercised directly via
///         `vm.prank(address(entryPoint))`, matching how the contract itself restricts those
///         calls — see {MockEntryPoint} for why a full `handleOps` simulation isn't needed.
contract EwpgPaymasterTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    MockOnchainId orgId;
    MockClaimIssuer kycIssuer;
    MockEntryPoint entryPoint;
    EwpgPaymaster paymaster;

    address operator = address(0x1);
    address alice = address(0x3); // KYC'd org member wallet — stands in for userOp.sender
    address mallory = address(0x66); // unbound wallet
    address sponsor = address(0x9);

    bytes32 configurePermission;
    uint256 topicKyc;
    bytes32 constant POLICY_ID = keccak256("issuer-demo-bond");

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        entryPoint = new MockEntryPoint();
        paymaster = new EwpgPaymaster(oracle, IEntryPoint(address(entryPoint)));

        configurePermission = paymaster.CONFIGURE();
        topicKyc = paymaster.TOPIC_KYC();

        orgId = new MockOnchainId();
        kycIssuer = new MockClaimIssuer();

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(orgId), alice, roles, "");
        permissions.grantToOrg(address(orgId), configurePermission);
        uint256[] memory topics = new uint256[](1);
        topics[0] = topicKyc;
        tir.addTrustedIssuer(address(kycIssuer), topics);
        vm.stopPrank();
        orgId.addClaim(topicKyc, address(kycIssuer), hex"01", hex"02");

        vm.deal(sponsor, 10 ether);
    }

    function _buildUserOp(address sender, bytes32 policyId) private view returns (PackedUserOperation memory) {
        return PackedUserOperation({
            sender: sender,
            nonce: 0,
            initCode: "",
            callData: "",
            accountGasLimits: bytes32(0),
            preVerificationGas: 0,
            gasFees: bytes32(0),
            paymasterAndData: abi.encodePacked(address(paymaster), uint128(100_000), uint128(50_000), policyId),
            signature: ""
        });
    }

    // ── funding ──────────────────────────────────────────────────────────────

    function test_fundSponsorship_increasesPolicyBalanceAndForwardsToEntryPoint() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);

        assertEq(paymaster.policyBalance(POLICY_ID), 1 ether);
        assertEq(entryPoint.deposits(address(paymaster)), 1 ether);
    }

    function test_setWalletBudgetCap_revertsForNonOperatorCaller() public {
        vm.prank(mallory);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, configurePermission)
        );
        paymaster.setWalletBudgetCap(POLICY_ID, 1 ether);
    }

    function test_setWalletBudgetCap_succeedsForPermissionedMember() public {
        vm.prank(alice);
        paymaster.setWalletBudgetCap(POLICY_ID, 1 ether);
        assertEq(paymaster.walletBudgetCap(POLICY_ID), 1 ether);
    }

    // ── validatePaymasterUserOp ──────────────────────────────────────────────

    function test_validatePaymasterUserOp_revertsForNonEntryPointCaller() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);

        vm.expectRevert(EwpgPaymaster.NotEntryPoint.selector);
        paymaster.validatePaymasterUserOp(_buildUserOp(alice, POLICY_ID), bytes32(0), 0.01 ether);
    }

    function test_validatePaymasterUserOp_revertsForUnboundWallet() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);

        vm.prank(address(entryPoint));
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotVerifiedMember.selector, mallory));
        paymaster.validatePaymasterUserOp(_buildUserOp(mallory, POLICY_ID), bytes32(0), 0.01 ether);
    }

    function test_validatePaymasterUserOp_revertsWithoutKycClaim() public {
        kycIssuer.setValid(false);
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);

        vm.prank(address(entryPoint));
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotVerifiedMember.selector, alice));
        paymaster.validatePaymasterUserOp(_buildUserOp(alice, POLICY_ID), bytes32(0), 0.01 ether);
    }

    function test_validatePaymasterUserOp_revertsWhenPolicyBudgetExceeded() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 0.001 ether}(POLICY_ID);

        vm.prank(address(entryPoint));
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.PolicyBudgetExceeded.selector, POLICY_ID));
        paymaster.validatePaymasterUserOp(_buildUserOp(alice, POLICY_ID), bytes32(0), 0.01 ether);
    }

    function test_validatePaymasterUserOp_revertsWhenWalletBudgetCapExceeded() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);
        vm.prank(alice);
        paymaster.setWalletBudgetCap(POLICY_ID, 0.005 ether);

        vm.prank(address(entryPoint));
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.WalletBudgetExceeded.selector, POLICY_ID, alice));
        paymaster.validatePaymasterUserOp(_buildUserOp(alice, POLICY_ID), bytes32(0), 0.01 ether);
    }

    function test_validatePaymasterUserOp_revertsWithoutPolicyIdInPaymasterData() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);

        PackedUserOperation memory userOp = _buildUserOp(alice, POLICY_ID);
        userOp.paymasterAndData = abi.encodePacked(address(paymaster), uint128(100_000), uint128(50_000)); // no policyId

        vm.prank(address(entryPoint));
        vm.expectRevert(EwpgPaymaster.MissingPolicyId.selector);
        paymaster.validatePaymasterUserOp(userOp, bytes32(0), 0.01 ether);
    }

    function test_validatePaymasterUserOp_succeedsAndReturnsContext() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);

        vm.prank(address(entryPoint));
        (bytes memory context, uint256 validationData) =
            paymaster.validatePaymasterUserOp(_buildUserOp(alice, POLICY_ID), bytes32(0), 0.01 ether);

        assertEq(validationData, 0, "packed (success, 0, 0) == 0");
        (bytes32 decodedPolicy, address decodedWallet) = abi.decode(context, (bytes32, address));
        assertEq(decodedPolicy, POLICY_ID);
        assertEq(decodedWallet, alice);
    }

    // ── postOp ───────────────────────────────────────────────────────────────

    function test_postOp_revertsForNonEntryPointCaller() public {
        bytes memory context = abi.encode(POLICY_ID, alice);
        vm.expectRevert(EwpgPaymaster.NotEntryPoint.selector);
        paymaster.postOp(IPaymaster.PostOpMode.opSucceeded, context, 0.005 ether, 1 gwei);
    }

    function test_postOp_deductsActualGasCostAndTracksWalletSpend() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);

        bytes memory context = abi.encode(POLICY_ID, alice);
        vm.prank(address(entryPoint));
        paymaster.postOp(IPaymaster.PostOpMode.opSucceeded, context, 0.005 ether, 1 gwei);

        assertEq(paymaster.policyBalance(POLICY_ID), 1 ether - 0.005 ether);
        assertEq(paymaster.spentByWallet(POLICY_ID, alice), 0.005 ether);
    }

    function test_endToEnd_validateThenPostOp_leavesConsistentAccounting() public {
        vm.prank(sponsor);
        paymaster.fundSponsorship{value: 1 ether}(POLICY_ID);

        vm.prank(address(entryPoint));
        (bytes memory context,) =
            paymaster.validatePaymasterUserOp(_buildUserOp(alice, POLICY_ID), bytes32(0), 0.01 ether);

        // Actual cost often comes in below the pre-validated maxCost.
        vm.prank(address(entryPoint));
        paymaster.postOp(IPaymaster.PostOpMode.opSucceeded, context, 0.006 ether, 1 gwei);

        assertEq(paymaster.policyBalance(POLICY_ID), 1 ether - 0.006 ether);
        assertEq(paymaster.spentByWallet(POLICY_ID, alice), 0.006 ether);
    }
}
