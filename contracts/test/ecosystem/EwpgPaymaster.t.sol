// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import {
    PackedUserOperation as OzPackedUserOperation,
    IEntryPoint as OzIEntryPoint,
    IPaymaster
} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {EntryPoint} from "@account-abstraction/contracts/core/EntryPoint.sol";
import {IEntryPoint} from "@account-abstraction/contracts/interfaces/IEntryPoint.sol";
import {IStakeManager} from "@account-abstraction/contracts/interfaces/IStakeManager.sol";
import {PackedUserOperation} from "@account-abstraction/contracts/interfaces/PackedUserOperation.sol";
import {IAccount} from "@account-abstraction/contracts/interfaces/IAccount.sol";
import {EcosystemTrustedIssuersRegistry} from "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import {EwpgPaymaster} from "../../src/ecosystem/EwpgPaymaster.sol";
import {OrgRegistry} from "../../src/ecosystem/OrgRegistry.sol";
import {PermissionOracle} from "../../src/ecosystem/PermissionOracle.sol";
import {PermissionRegistry} from "../../src/ecosystem/PermissionRegistry.sol";
import {RegisterwerkGated} from "../../src/ecosystem/RegisterwerkGated.sol";
import {MockClaimIssuer} from "./mocks/MockClaimIssuer.sol";
import {MockOnchainId} from "./mocks/MockOnchainId.sol";

/// @dev A member smart account that accepts every UserOperation (its own signature scheme is
///      irrelevant to the paymaster) and executes nothing.
contract AcceptAllAccount is IAccount {
    function validateUserOp(PackedUserOperation calldata, bytes32, uint256) external pure returns (uint256) {
        return 0;
    }

    fallback() external payable {}
    receive() external payable {}
}

/// @notice {EwpgPaymaster} against the real EntryPoint v0.8.0 (the pinned `lib/account-abstraction` submodule):
///         every sponsored path goes through `handleOps`, so the gas accounting below is the
///         EntryPoint's own, not a mock's. Ports the phase-2 PoCs `PaymasterDrain.t.sol`
///         (T2-01) and adds the T2-01b / T2-02 regressions.
contract EwpgPaymasterTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    MockOnchainId orgId;
    MockOnchainId issuerOrgId;
    MockClaimIssuer kycIssuer;
    EntryPoint ep;
    EwpgPaymaster pm;

    AcceptAllAccount acctA; // KYC'd member wallet (org A)
    AcceptAllAccount acctB; // second wallet of the same org
    AcceptAllAccount mallory; // also a KYC'd member — the attacker only needs to be one

    address operator = address(0x1);
    address configurer = address(0xC0F1); // org member holding paymaster.configure
    address issuerTreasury = address(0x15517E5);
    address bundler = address(0xB0D1E5);
    address stranger = address(0x5712);

    uint256 constant SIGNER_PK = 0x5157;
    address voucherSigner;

    bytes32 constant POLICY = keccak256("issuer-demo-bond");
    uint128 constant PM_VERIFICATION_GAS = 150_000;
    uint128 constant PM_POSTOP_GAS = 80_000;
    uint128 constant FEE_CAP = 10 gwei;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        ep = new EntryPoint();
        orgId = new MockOnchainId(); // the paymaster's operator org
        pm = new EwpgPaymaster(oracle, OzIEntryPoint(address(ep)), address(orgId));

        acctA = new AcceptAllAccount();
        acctB = new AcceptAllAccount();
        mallory = new AcceptAllAccount();
        voucherSigner = vm.addr(SIGNER_PK);

        kycIssuer = new MockClaimIssuer();
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(orgId), configurer, roles, "");
        orgRegistry.addMember(address(orgId), address(acctA), roles, "");
        orgRegistry.addMember(address(orgId), address(acctB), roles, "");
        orgRegistry.addMember(address(orgId), address(mallory), roles, "");
        permissions.grantToOrg(address(orgId), pm.CONFIGURE());
        // The issuer funds its policy: an approved sponsor (register-policy) of its own org.
        issuerOrgId = new MockOnchainId();
        orgRegistry.registerOrg(address(issuerOrgId), 276);
        orgRegistry.addMember(address(issuerOrgId), issuerTreasury, roles, "");
        permissions.grantToOrg(address(issuerOrgId), pm.REGISTER_POLICY());
        uint256[] memory topics = new uint256[](1);
        topics[0] = pm.TOPIC_KYC();
        tir.addTrustedIssuer(address(kycIssuer), topics);
        vm.stopPrank();
        orgId.addClaim(pm.TOPIC_KYC(), address(kycIssuer), hex"01", hex"02");

        vm.deal(issuerTreasury, 100 ether);
        vm.prank(issuerTreasury);
        pm.registerPolicy{value: 10 ether}(POLICY, voucherSigner, 5 ether);

        vm.fee(1 gwei);
        vm.txGasPrice(1 gwei);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    function _op(address sender, uint256 nonce, uint128 callGas, uint256 fee)
        internal
        pure
        returns (PackedUserOperation memory op)
    {
        op.sender = sender;
        op.nonce = nonce;
        op.callData = hex"";
        op.accountGasLimits = bytes32((uint256(100_000) << 128) | uint256(callGas));
        op.preVerificationGas = 21_000;
        op.gasFees = bytes32((fee << 128) | fee);
    }

    function _hash(PackedUserOperation memory op, bytes32 policyId, uint48 until, uint48 after_, uint128 cap)
        internal
        view
        returns (bytes32)
    {
        OzPackedUserOperation memory oz = abi.decode(abi.encode(op), (OzPackedUserOperation));
        return pm.getHash(oz, policyId, until, after_, cap);
    }

    /// Attaches a voucher for `policyId` signed by `pk` over `signedOp` (normally `op` itself).
    function _voucher(
        PackedUserOperation memory op,
        PackedUserOperation memory signedOp,
        bytes32 policyId,
        uint256 pk,
        uint48 until,
        uint128 cap
    ) internal view returns (PackedUserOperation memory) {
        bytes memory prefix = abi.encodePacked(address(pm), PM_VERIFICATION_GAS, PM_POSTOP_GAS);
        // getHash reads the paymaster gas limits from paymasterAndData, so set the prefix first.
        signedOp.paymasterAndData = abi.encodePacked(prefix, policyId, until, uint48(0), cap, new bytes(65));
        bytes32 digest = MessageHashUtils.toEthSignedMessageHash(_hash(signedOp, policyId, until, 0, cap));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(pk, digest);
        op.paymasterAndData = abi.encodePacked(prefix, policyId, until, uint48(0), cap, r, s, v);
        return op;
    }

    function _signed(PackedUserOperation memory op) internal view returns (PackedUserOperation memory) {
        return _voucher(op, op, POLICY, SIGNER_PK, uint48(block.timestamp + 300), FEE_CAP);
    }

    function _handle(PackedUserOperation memory op) internal {
        PackedUserOperation[] memory ops = new PackedUserOperation[](1);
        ops[0] = op;
        vm.prank(bundler, bundler);
        ep.handleOps(ops, payable(bundler));
    }

    function _aa33(bytes memory inner) internal pure returns (bytes memory) {
        return abi.encodeWithSelector(IEntryPoint.FailedOpWithRevert.selector, 0, "AA33 reverted", inner);
    }

    // ── T2-01b: postOp books what the EntryPoint really charges ──────────────

    function test_honestOp_bookedChargeCoversRealDepositDelta() public {
        uint256 depBefore = ep.balanceOf(address(pm));
        _handle(_signed(_op(address(acctA), 0, 100_000, 2 gwei)));

        uint256 delta = depBefore - ep.balanceOf(address(pm));
        uint256 booked = pm.spentByOrg(POLICY, address(orgId));
        uint256 price = 2 gwei; // min(maxFee, basefee 1 gwei + priority 2 gwei)
        assertGt(delta, 0);
        // HEAD b810acb booked the pre-postOp `actualGasCost` only => booked < delta (drift).
        assertGe(booked, delta, "books must cover the real EntryPoint debit");
        assertLe(booked - delta, (uint256(PM_POSTOP_GAS) + pm.POST_OP_OVERHEAD_GAS()) * price);
        assertEq(pm.policyReserved(POLICY), 0);
        assertEq(pm.reservedByOrg(POLICY, address(orgId)), 0);
        assertEq(pm.policyBalance(POLICY), 10 ether - booked);
        assertGe(pm.depositSurplus(), 0);
    }

    // ── T2-01: no voucher / foreign voucher / inflated gas ───────────────────

    /// PoC A (`test_selfBundleCashOut`): the HEAD paymaster sponsored any member that merely
    /// named a funded policyId, at a self-chosen gas price. Without a voucher it now reverts.
    function test_selfBundleCashOut_withoutVoucher_reverts() public {
        PackedUserOperation memory op = _op(address(mallory), 0, 3_000_000, 1000 gwei);
        op.paymasterAndData = abi.encodePacked(address(pm), PM_VERIFICATION_GAS, PM_POSTOP_GAS, POLICY);
        vm.expectRevert(_aa33(abi.encodeWithSelector(EwpgPaymaster.InvalidPaymasterData.selector)));
        _handle(op);
        assertEq(bundler.balance, 0);
    }

    function test_selfBundleCashOut_inflatedGasPriceAboveVoucherCap_reverts() public {
        PackedUserOperation memory op = _signed(_op(address(mallory), 0, 3_000_000, 1000 gwei));
        vm.expectRevert(
            _aa33(abi.encodeWithSelector(EwpgPaymaster.GasPriceAboveCap.selector, 1000 gwei, uint256(FEE_CAP)))
        );
        _handle(op);
    }

    function test_voucherForAnotherSender_failsSignature() public {
        PackedUserOperation memory victimOp = _op(address(acctA), 0, 100_000, 2 gwei);
        PackedUserOperation memory malloryOp = _op(address(mallory), 0, 100_000, 2 gwei);
        malloryOp = _voucher(malloryOp, victimOp, POLICY, SIGNER_PK, uint48(block.timestamp + 300), FEE_CAP);
        vm.expectRevert(abi.encodeWithSelector(IEntryPoint.FailedOp.selector, 0, "AA34 signature error"));
        _handle(malloryOp);
    }

    function test_voucherFromWrongSigner_failsSignature() public {
        PackedUserOperation memory op = _op(address(mallory), 0, 100_000, 2 gwei);
        op = _voucher(op, op, POLICY, 0xBAD, uint48(block.timestamp + 300), FEE_CAP);
        vm.expectRevert(abi.encodeWithSelector(IEntryPoint.FailedOp.selector, 0, "AA34 signature error"));
        _handle(op);
    }

    function test_voucherGasFieldsAreSigned() public {
        PackedUserOperation memory signedOp = _op(address(acctA), 0, 100_000, 2 gwei);
        PackedUserOperation memory op = _op(address(acctA), 0, 2_000_000, 2 gwei); // bigger callGas
        op = _voucher(op, signedOp, POLICY, SIGNER_PK, uint48(block.timestamp + 300), FEE_CAP);
        vm.expectRevert(abi.encodeWithSelector(IEntryPoint.FailedOp.selector, 0, "AA34 signature error"));
        _handle(op);
    }

    function test_expiredVoucher_rejected() public {
        PackedUserOperation memory op = _op(address(acctA), 0, 100_000, 2 gwei);
        op = _voucher(op, op, POLICY, SIGNER_PK, uint48(block.timestamp + 60), FEE_CAP);
        vm.warp(block.timestamp + 61);
        vm.expectRevert(abi.encodeWithSelector(IEntryPoint.FailedOp.selector, 0, "AA32 paymaster expired or not due"));
        _handle(op);
    }

    function test_getHash_bindsChainAndPaymaster() public {
        PackedUserOperation memory op = _op(address(acctA), 0, 100_000, 2 gwei);
        op.paymasterAndData = abi.encodePacked(address(pm), PM_VERIFICATION_GAS, PM_POSTOP_GAS);
        bytes32 h1 = _hash(op, POLICY, 1, 0, FEE_CAP);
        vm.chainId(block.chainid + 1);
        assertTrue(_hash(op, POLICY, 1, 0, FEE_CAP) != h1, "chainId bound");
    }

    // ── T2-01: reservation stops batch overspend ─────────────────────────────

    /// PoC B (`test_bundleBypassesCapAndPolicyBalance`): 30 ops validated against the same
    /// 0.1 ETH balance and drained 0.249 ETH of *other* sponsors' deposit. Now validation
    /// reserves, so the bundle is rejected at the first op that no longer fits.
    function test_bundle_cannotOverspendPolicy() public {
        bytes32 tiny = keccak256("tiny-policy");
        vm.prank(issuerTreasury);
        pm.registerPolicy{value: 0.1 ether}(tiny, voucherSigner, 1 ether);

        uint256 n = 30;
        PackedUserOperation[] memory ops = new PackedUserOperation[](n);
        for (uint256 i = 0; i < n; i++) {
            PackedUserOperation memory op = _op(address(mallory), i, 50_000, 10 gwei);
            ops[i] = _voucher(op, op, tiny, SIGNER_PK, uint48(block.timestamp + 300), FEE_CAP);
        }
        vm.prank(bundler, bundler);
        vm.expectRevert(); // FailedOpWithRevert(k, "AA33 reverted", PolicyBudgetExceeded(tiny))
        ep.handleOps(ops, payable(bundler));

        // A bundler then drops the failing ops; replay one at a time until the budget is gone.
        uint256 depBefore = ep.balanceOf(address(pm));
        uint256 included;
        for (uint256 i = 0; i < n; i++) {
            PackedUserOperation memory op = _op(address(mallory), included, 50_000, 10 gwei);
            op = _voucher(op, op, tiny, SIGNER_PK, uint48(block.timestamp + 300), FEE_CAP);
            PackedUserOperation[] memory one = new PackedUserOperation[](1);
            one[0] = op;
            vm.prank(bundler, bundler);
            try ep.handleOps(one, payable(bundler)) {
                included++;
            } catch {
                break;
            }
        }
        assertGt(included, 0);
        uint256 drained = depBefore - ep.balanceOf(address(pm));
        assertLe(drained, 0.1 ether, "never more than the policy's own funding");
        assertLe(pm.spentByOrg(tiny, address(orgId)), 0.1 ether);
        assertEq(pm.policyBalance(POLICY), 10 ether, "victim policy untouched");
        assertGe(pm.depositSurplus(), 0, "books solvent");
    }

    function test_orgCap_isSharedAcrossWalletsOfOneOrg() public {
        bytes32 capped = keccak256("capped-policy");
        vm.prank(issuerTreasury);
        // One op at 10 gwei reserves ~(100k+100k+150k+80k+21k) * 10 gwei ≈ 0.0045 ETH.
        pm.registerPolicy{value: 1 ether}(capped, voucherSigner, 0.006 ether);

        PackedUserOperation memory op1 = _op(address(acctA), 0, 100_000, 10 gwei);
        _handle(_voucher(op1, op1, capped, SIGNER_PK, uint48(block.timestamp + 300), FEE_CAP));

        // A fresh wallet of the same org cannot open a second allowance.
        PackedUserOperation memory op2 = _op(address(acctB), 0, 100_000, 10 gwei);
        op2 = _voucher(op2, op2, capped, SIGNER_PK, uint48(block.timestamp + 300), FEE_CAP);
        vm.expectRevert(_aa33(abi.encodeWithSelector(EwpgPaymaster.OrgBudgetExceeded.selector, capped, address(orgId))));
        _handle(op2);
    }

    function test_nonMemberSender_rejected() public {
        AcceptAllAccount outsider = new AcceptAllAccount();
        PackedUserOperation memory op = _signed(_op(address(outsider), 0, 100_000, 2 gwei));
        vm.expectRevert(_aa33(abi.encodeWithSelector(EwpgPaymaster.NotVerifiedMember.selector, address(outsider))));
        _handle(op);
    }

    function test_postOpGasLimitBelowMinimum_rejected() public {
        PackedUserOperation memory op = _op(address(acctA), 0, 100_000, 2 gwei);
        op.paymasterAndData = abi.encodePacked(
            address(pm), PM_VERIFICATION_GAS, uint128(10_000), POLICY, uint48(0), uint48(0), FEE_CAP, new bytes(65)
        );
        vm.expectRevert(_aa33(abi.encodeWithSelector(EwpgPaymaster.PostOpGasLimitTooLow.selector, 10_000)));
        _handle(op);
    }

    function test_validateAndPostOp_onlyEntryPoint() public {
        OzPackedUserOperation memory oz;
        vm.expectRevert(EwpgPaymaster.NotEntryPoint.selector);
        pm.validatePaymasterUserOp(oz, bytes32(0), 0);
        vm.expectRevert(EwpgPaymaster.NotEntryPoint.selector);
        pm.postOp(IPaymaster.PostOpMode.opSucceeded, "", 0, 0);
    }

    // ── T2-02: on-chain active flag, funder-only withdraw, stake ─────────────

    function test_inactivePolicy_rejectsVouchers() public {
        vm.prank(configurer);
        pm.setPolicyActive(POLICY, false);
        PackedUserOperation memory op = _signed(_op(address(acctA), 0, 100_000, 2 gwei));
        vm.expectRevert(_aa33(abi.encodeWithSelector(EwpgPaymaster.PolicyInactive.selector, POLICY)));
        _handle(op);
    }

    function test_unregisteredPolicy_rejected() public {
        bytes32 unknown = keccak256("unknown");
        PackedUserOperation memory op = _op(address(acctA), 0, 100_000, 2 gwei);
        op = _voucher(op, op, unknown, SIGNER_PK, uint48(block.timestamp + 300), FEE_CAP);
        vm.expectRevert(_aa33(abi.encodeWithSelector(EwpgPaymaster.PolicyInactive.selector, unknown)));
        _handle(op);
    }

    function test_withdrawPolicy_paysFunderOnly() public {
        uint256 before = issuerTreasury.balance;
        // A configure holder can trigger the refund, but it lands with the funder.
        vm.prank(configurer);
        pm.withdrawPolicy(POLICY, 4 ether);
        assertEq(issuerTreasury.balance, before + 4 ether);
        assertEq(configurer.balance, 0);
        assertEq(pm.policyBalance(POLICY), 6 ether);
        assertEq(ep.balanceOf(address(pm)), 6 ether);

        vm.prank(stranger);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotPolicyAdmin.selector, POLICY, stranger));
        pm.withdrawPolicy(POLICY, 1 ether);

        vm.prank(issuerTreasury);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.PolicyBudgetExceeded.selector, POLICY));
        pm.withdrawPolicy(POLICY, 6 ether + 1);
    }

    function test_onlyFunderFundsAndRotatesSigner() public {
        vm.deal(configurer, 1 ether);
        vm.prank(configurer);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotPolicyFunder.selector, POLICY, configurer));
        pm.fundSponsorship{value: 1 ether}(POLICY);

        // Not even paymaster.configure may swap the signer: a signer can spend the policy.
        vm.prank(configurer);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotPolicyFunder.selector, POLICY, configurer));
        pm.setPolicySigner(POLICY, configurer);

        vm.prank(issuerTreasury);
        pm.setPolicySigner(POLICY, address(0xABC));
        assertEq(pm.policySigner(POLICY), address(0xABC));
    }

    function test_registerPolicy_guards() public {
        vm.startPrank(issuerTreasury);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.PolicyAlreadyRegistered.selector, POLICY));
        pm.registerPolicy(POLICY, stranger, 1 ether);

        vm.expectRevert(EwpgPaymaster.ZeroSigner.selector);
        pm.registerPolicy(keccak256("p2"), address(0), 1 ether);
        vm.expectRevert(EwpgPaymaster.ZeroOrgCap.selector);
        pm.registerPolicy(keccak256("p2"), voucherSigner, 0);
        vm.stopPrank();
    }

    /// @notice H12: registering needs `paymaster.register-policy` via the caller's org — an
    ///         unbound wallet, and a member of an org without the grant, are both refused.
    function test_registerPolicy_requiresRegisterPolicyPermission() public {
        bytes32 id = keccak256("p3");
        bytes32 permission = pm.REGISTER_POLICY();

        vm.prank(stranger);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, stranger, permission));
        pm.registerPolicy(id, voucherSigner, 1 ether);

        // The operator org holds only paymaster.configure — not the right to open sponsor policies.
        vm.prank(configurer);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, configurer, permission));
        pm.registerPolicy(id, voucherSigner, 1 ether);

        vm.prank(issuerTreasury);
        pm.registerPolicy(id, voucherSigner, 1 ether);
        assertEq(pm.funder(id), issuerTreasury);
    }

    /// @notice H12: the `paymaster.configure` safety valve is bound to the operator org, exactly like
    ///         `RegisterwerkGated.requiresOrgPermission` on every other instance-bound dApp.
    function test_foreignOrgConfigureHolder_isRefusedWithTheOperatingOrgError() public {
        address foreign = _foreignConfigurer();
        vm.deal(foreign, 1 ether);

        vm.startPrank(foreign);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotPolicyAdmin.selector, POLICY, foreign));
        pm.setPolicyActive(POLICY, false);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotPolicyAdmin.selector, POLICY, foreign));
        pm.setOrgBudgetCap(POLICY, 1);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotPolicyAdmin.selector, POLICY, foreign));
        pm.withdrawPolicy(POLICY, 1 ether);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, foreign, address(orgId))
        );
        pm.addStake{value: 1 ether}(1 days);
        vm.stopPrank();
    }

    /// @notice The stake funder's de-stake path is also org-bound for the safety-valve caller.
    function test_withdrawStake_foreignOrgConfigureHolderCannotRedirectOrWithdraw() public {
        vm.deal(configurer, 1 ether);
        vm.prank(configurer);
        pm.addStake{value: 1 ether}(1 days);
        vm.prank(configurer);
        pm.unlockStake();
        vm.warp(block.timestamp + 1 days + 1);

        address foreign = _foreignConfigurer();
        vm.prank(foreign);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotStakeAdmin.selector, foreign));
        pm.withdrawStake();
    }

    function test_addStake_makesPaymasterStaked_andWithdrawPaysStakeFunder() public {
        vm.deal(configurer, 2 ether);
        vm.prank(configurer);
        pm.addStake{value: 1 ether}(1 days);
        IStakeManager.DepositInfo memory info = ep.getDepositInfo(address(pm));
        assertTrue(info.staked);
        assertEq(info.stake, 1 ether);

        vm.prank(stranger);
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotStakeAdmin.selector, stranger));
        pm.unlockStake();

        vm.prank(configurer);
        pm.unlockStake();
        vm.warp(block.timestamp + 1 days + 1);
        vm.prank(configurer);
        pm.withdrawStake();
        assertEq(configurer.balance, 2 ether);
        assertEq(pm.stakeFunder(), address(0));
    }

    /// @notice Veto N5: any wallet holding the org-wide `paymaster.configure` grant could
    ///         `unlockStake()` and de-stake the paymaster (bundlers then drop it). Only the
    ///         stake funder may start the unstake delay now.
    function test_unlockStake_onlyStakeFunder_notOtherConfigureHolders() public {
        vm.deal(configurer, 1 ether);
        vm.prank(configurer);
        pm.addStake{value: 1 ether}(1 days);
        assertTrue(oracle.hasPermission(address(mallory), pm.CONFIGURE()), "mallory holds configure");

        vm.prank(address(mallory));
        vm.expectRevert(abi.encodeWithSelector(EwpgPaymaster.NotStakeAdmin.selector, address(mallory)));
        pm.unlockStake();
        assertTrue(ep.getDepositInfo(address(pm)).staked, "still staked");

        vm.prank(configurer);
        pm.unlockStake();
        assertFalse(ep.getDepositInfo(address(pm)).staked);
    }

    function test_addStake_requiresConfigure() public {
        vm.deal(stranger, 1 ether);
        bytes32 configure = pm.CONFIGURE();
        vm.prank(stranger);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, stranger, address(orgId)));
        pm.addStake{value: 1 ether}(1 days);

        // A member of the operator org without the grant is refused on the permission itself.
        address bareMember = address(0xBA5E);
        vm.startPrank(operator);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        MockOnchainId bareOrg = new MockOnchainId();
        orgRegistry.registerOrg(address(bareOrg), 276);
        orgRegistry.addMember(address(bareOrg), bareMember, roles, "");
        vm.stopPrank();
        EwpgPaymaster bound = new EwpgPaymaster(oracle, OzIEntryPoint(address(ep)), address(bareOrg));
        vm.deal(bareMember, 1 ether);
        vm.prank(bareMember);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, bareMember, configure));
        bound.addStake{value: 1 ether}(1 days);
    }

    // ── H12: org-bound admin + no policy-id squatting + KYC claim check ─────────────────────────

    /// @dev A wallet in a *different* org that also holds the org-wide `paymaster.configure` grant —
    ///      e.g. any issuer the operator once granted it to. It must not reach this paymaster's
    ///      operator-only functions.
    function _foreignConfigurer() internal returns (address wallet) {
        wallet = address(0xF0E16);
        MockOnchainId foreignOrg = new MockOnchainId();
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(foreignOrg), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(foreignOrg), wallet, roles, "");
        permissions.grantToOrg(address(foreignOrg), pm.CONFIGURE());
        vm.stopPrank();
    }

    /// @notice H12 (red-first): the `paymaster.configure` safety valve was unbound — a holder in any
    ///         org could deactivate another sponsor's policy, change its cap or trigger its refund.
    function test_foreignOrgConfigureHolder_cannotAdministerAPolicy() public {
        address foreign = _foreignConfigurer();

        vm.startPrank(foreign);
        try pm.setPolicyActive(POLICY, false) {} catch {}
        try pm.setOrgBudgetCap(POLICY, 1) {} catch {}
        try pm.withdrawPolicy(POLICY, 1 ether) {} catch {}
        vm.stopPrank();

        assertTrue(pm.policyActive(POLICY), "a foreign org's configure holder deactivated the policy");
        assertEq(pm.orgBudgetCap(POLICY), 5 ether, "a foreign org's configure holder changed the cap");
        assertEq(pm.policyBalance(POLICY), 10 ether, "a foreign org's configure holder withdrew budget");
    }

    function test_foreignOrgConfigureHolder_cannotStakeOrUnstake() public {
        address foreign = _foreignConfigurer();
        vm.deal(foreign, 1 ether);
        vm.prank(foreign);
        try pm.addStake{value: 1 ether}(1 days) {} catch {}
        assertEq(pm.stakeFunder(), address(0), "a foreign org's configure holder became the stake funder");
    }

    /// @notice H12 (red-first): anyone could register an unregistered policy id — including one whose
    ///         id the real funder had already published (the backend shows `keccak256(policyRowId)`),
    ///         front-running it with their own signer and locking the funder out.
    function test_registerPolicy_cannotBeSquattedByAWalletWithoutThePermission() public {
        bytes32 victimPolicy = keccak256("policy-row-victim");
        address squatter = address(0x5A7);
        vm.deal(squatter, 1 ether);

        vm.prank(squatter);
        try pm.registerPolicy(victimPolicy, squatter, 1 ether) {} catch {}

        assertEq(pm.funder(victimPolicy), address(0), "an unpermissioned wallet squatted the policy id");
    }

    /// @notice Restores `revertsWithoutKycClaim`, removed with the pre-voucher paymaster in phase 2: a
    ///         member whose org no longer carries a valid KYC claim is not sponsored.
    function test_org_withoutValidKycClaim_isNotSponsored() public {
        kycIssuer.setValid(false);
        PackedUserOperation memory op = _signed(_op(address(acctA), 0, 100_000, 2 gwei));
        vm.expectRevert(_aa33(abi.encodeWithSelector(EwpgPaymaster.NotVerifiedMember.selector, address(acctA))));
        _handle(op);

        kycIssuer.setValid(true);
        _handle(_signed(_op(address(acctA), 0, 100_000, 2 gwei))); // sponsored again once the claim is valid
    }

    /// Cross-language vector: `GasSponsorshipVoucherDigestTest` (backend) pins the same value,
    /// so the voucher issuer's Java digest and {EwpgPaymaster.getHash} cannot drift apart.
    function test_getHash_pinnedVector() public {
        address fixedPm = address(0x2222222222222222222222222222222222222222);
        vm.etch(fixedPm, address(pm).code);
        vm.chainId(31337);
        PackedUserOperation memory op;
        op.sender = address(0x1111111111111111111111111111111111111111);
        op.nonce = 7;
        op.initCode = hex"7702";
        op.callData = hex"deadbeef";
        op.accountGasLimits = bytes32((uint256(100_000) << 128) | uint256(200_000));
        op.preVerificationGas = 21_000;
        op.gasFees = bytes32((uint256(1 gwei) << 128) | uint256(10 gwei));
        op.paymasterAndData = abi.encodePacked(fixedPm, uint128(150_000), uint128(80_000));
        OzPackedUserOperation memory oz = abi.decode(abi.encode(op), (OzPackedUserOperation));
        bytes32 h = EwpgPaymaster(fixedPm).getHash(oz, keccak256("policy"), 1_700_000_000, 0, 10 gwei);
        emit log_named_bytes32("voucher digest", h);
        assertEq(h, 0xee2ca28f7c343c5f133cbe451ce09a964bb4a54fbb14add797f6db6db841b53e);
    }
}
