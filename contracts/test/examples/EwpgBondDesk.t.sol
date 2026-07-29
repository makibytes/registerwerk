// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "@erc3643/ERC-3643/IERC3643.sol";
import "@erc3643/ERC-3643/IERC3643IdentityRegistry.sol";
import "@erc3643/factory/ITREXFactory.sol";
import "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import "@onchain-id/solidity/contracts/interface/IIdentity.sol";

import "../../src/compliance/EwpgModularCompliance.sol";
import "../../src/examples/EwpgBondDesk.sol";
import "../helpers/TrexSuiteDeployer.sol";

// The ecosystem side declares its own minimal `IIdentity`/`IClaimIssuer` interfaces
// (contracts/src/ecosystem/interfaces/) that collide by name with the real onchain-id
// ones pulled in above via IERC3643IdentityRegistry/ClaimIssuer. Selective imports pull
// in only the named contract, not the rest of each file's transitive import graph, so
// the two `IIdentity`/`IClaimIssuer` pairs never end up in the same namespace.
import {EcosystemTrustedIssuersRegistry} from "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import {OrgRegistry} from "../../src/ecosystem/OrgRegistry.sol";
import {PermissionOracle} from "../../src/ecosystem/PermissionOracle.sol";
import {PermissionRegistry} from "../../src/ecosystem/PermissionRegistry.sol";
import {RegisterwerkGated} from "../../src/ecosystem/RegisterwerkGated.sol";
import {MockClaimIssuer} from "../ecosystem/mocks/MockClaimIssuer.sol";
import {MockOnchainId} from "../ecosystem/mocks/MockOnchainId.sol";
import {MockStablecoin} from "../../src/examples/MockStablecoin.sol";

/// @dev T-REX `Token`/`AgentRoleUpgradeable` exposes `addAgent` but it isn't part of the
///      `IERC3643`/`IToken` interfaces (it's mixed in via the agent-role base), so a
///      minimal local interface is enough to call it on the deployed token proxy.
interface IAgentRole {
    function addAgent(address agent) external;
}

/// @dev T-REX's proxies use `OwnableOnceNext2StepUpgradeable` (via `AgentRoleUpgradeable`
///      / `OwnableOnceNext2StepUpgradeable` bases), not plain OZ `Ownable` — despite its
///      name, its `firstCall` flag defaults to `false` on a fresh proxy, so *every*
///      `transferOwnership` call, including the factory's very first one in
///      `deployTREXSuite`, only sets a pending owner. The recipient must call
///      `acceptOwnership()` before any `onlyOwner` function (like `addAgent`) will work.
interface IOwnable2Step {
    function acceptOwnership() external;
}

/// @notice Full-stack integration test for {EwpgBondDesk}: a real T-REX (ERC-3643) bond
///         suite is bootstrapped end to end (implementation authority, ONCHAINID
///         factory, identity registry, modular compliance with {EwpgComplianceModule}),
///         then gated through the Registerwerk ecosystem exactly as a marketplace dApp
///         would be. Two independent identity layers are exercised deliberately:
///           - the *investor's* ONCHAINID, verified by the T-REX identity registry via a
///             real onchain-id {ClaimIssuer} issuing ECDSA-signed KYC/AML claims;
///           - the *operating org's* ONCHAINID (a {MockOnchainId}, as in the other
///             ecosystem tests), checked by the {PermissionOracle} to gate who may call
///             the desk at all.
///         Both must independently hold for `issue`/`payCoupon`/`redeem` to succeed —
///         see the contract-level NatSpec on {EwpgBondDesk} for why that's the point.
contract EwpgBondDeskTest is Test {
    uint256 internal constant TOPIC_KYC = 1;
    uint256 internal constant TOPIC_AML = 2;
    uint16 internal constant COUNTRY_DE = 276;
    uint16 internal constant COUNTRY_BLOCKED = 850;
    uint16 internal constant COUPON_BPS = 450; // 4.50% per coupon period
    uint256 internal constant PRICE_PER_UNIT = 100e6; // 100.00 EUR face value, 6-decimals EMT
    uint256 internal constant COUPON_INTERVAL = 90 days;
    string internal constant SALT = "demo-bond-2029";

    // ecosystem
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    MockOnchainId orgId;
    MockClaimIssuer ecosystemKycIssuer;
    MockClaimIssuer ecosystemAmlIssuer;

    // T-REX + ONCHAINID infra
    TrexDeployment trex;
    ClaimIssuer claimIssuer;
    uint256 issuerKey;

    // bond suite
    EwpgComplianceModule complianceModule;
    IERC3643 bond;
    IERC3643IdentityRegistry identityRegistry;
    address complianceAddr;

    EwpgBondDesk desk;
    MockStablecoin stable; // MiCAR EMT stand-in (AUEUR, 6 decimals)
    bytes32 issue_;
    bytes32 payCoupon_;
    bytes32 redeem_;
    bytes32 pause_;
    uint256 maturity;

    address operator = address(0x1);
    address alice = address(0x3); // org treasury member operating the desk
    address mallory = address(0x66); // unbound wallet
    address issuerTreasury = address(0x7); // receives subscriptions, funds coupons/redemptions

    address investor1;
    address investor1Identity;
    address investor2; // dedicated to the blocked-country scenario
    address investor2Identity;
    address investor3;
    address investor3Identity;
    address investor4;
    address investor4Identity;

    function setUp() public {
        // ── Ecosystem (org identity + permissions) ────────────────────────────
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        // ── T-REX + ONCHAINID bring-up ─────────────────────────────────────────
        trex = TrexSuiteDeployer.deploy(operator);

        // Real onchain-id claim issuer for investor-level T-REX KYC/AML claims.
        (address issuerSigner, uint256 key) = makeAddrAndKey("bondClaimIssuer");
        issuerKey = key;
        claimIssuer = new ClaimIssuer(issuerSigner);

        (investor1, investor1Identity) = _createVerifiedInvestor("investor1");
        (investor2, investor2Identity) = _createVerifiedInvestor("investor2");
        (investor3, investor3Identity) = _createVerifiedInvestor("investor3");
        (investor4, investor4Identity) = _createVerifiedInvestor("investor4");

        // ── Compliance module + bond suite ─────────────────────────────────────
        complianceModule = new EwpgComplianceModule();

        address[] memory irAgents = new address[](1);
        irAgents[0] = operator;
        // operator is also a token agent so it can unpause the token below — T-REX
        // tokens always deploy paused (Token.init sets _tokenPaused = true).
        address[] memory tokenAgents = new address[](1);
        tokenAgents[0] = operator;
        address[] memory complianceModules = new address[](1);
        complianceModules[0] = address(complianceModule);

        ITREXFactory.TokenDetails memory tokenDetails = ITREXFactory.TokenDetails({
            owner: operator,
            name: "Meridian Infrastructure Bond 2029",
            symbol: "MIB29",
            decimals: 0,
            irs: address(0),
            ONCHAINID: address(0),
            irAgents: irAgents,
            tokenAgents: tokenAgents,
            complianceModules: complianceModules,
            complianceSettings: new bytes[](0)
        });

        uint256[] memory claimTopics = new uint256[](2);
        claimTopics[0] = TOPIC_KYC;
        claimTopics[1] = TOPIC_AML;
        address[] memory issuers = new address[](1);
        issuers[0] = address(claimIssuer);
        uint256[][] memory issuerClaims = new uint256[][](1);
        issuerClaims[0] = claimTopics;

        ITREXFactory.ClaimDetails memory claimDetails =
            ITREXFactory.ClaimDetails({claimTopics: claimTopics, issuers: issuers, issuerClaims: issuerClaims});

        vm.prank(operator);
        address tokenAddress =
            trex.factory.deployEwpgSuite(keccak256(bytes(SALT)), SALT, tokenDetails, claimDetails);

        bond = IERC3643(tokenAddress);
        identityRegistry = bond.identityRegistry();
        complianceAddr = address(bond.compliance());

        vm.startPrank(operator);
        identityRegistry.registerIdentity(investor1, IIdentity(investor1Identity), COUNTRY_DE);
        identityRegistry.registerIdentity(investor2, IIdentity(investor2Identity), COUNTRY_DE);
        identityRegistry.registerIdentity(investor3, IIdentity(investor3Identity), COUNTRY_DE);
        identityRegistry.registerIdentity(investor4, IIdentity(investor4Identity), COUNTRY_DE);
        vm.stopPrank();

        // ── Payment leg: MiCAR EMT stablecoin + funded issuer treasury ──────────
        stable = new MockStablecoin("AllUnity Euro", "AUEUR", 6);
        stable.mint(issuerTreasury, 10_000_000e6);
        stable.mint(investor1, 1_000_000e6);
        stable.mint(investor2, 1_000_000e6);
        stable.mint(investor3, 1_000_000e6);
        stable.mint(investor4, 1_000_000e6);

        // ── The dApp itself ─────────────────────────────────────────────────────
        maturity = block.timestamp + 365 days;
        desk = new EwpgBondDesk(
            oracle, bond, stable, issuerTreasury, PRICE_PER_UNIT, COUPON_BPS, COUPON_INTERVAL, maturity
        );
        issue_ = desk.ISSUE();
        payCoupon_ = desk.PAY_COUPON();
        redeem_ = desk.REDEEM();
        pause_ = desk.PAUSE();

        // Treasury funds coupon/redemption payouts; investors fund their subscriptions.
        vm.prank(issuerTreasury);
        stable.approve(address(desk), type(uint256).max);
        vm.prank(investor1);
        stable.approve(address(desk), type(uint256).max);
        vm.prank(investor2);
        stable.approve(address(desk), type(uint256).max);
        vm.prank(investor3);
        stable.approve(address(desk), type(uint256).max);
        vm.prank(investor4);
        stable.approve(address(desk), type(uint256).max);

        vm.startPrank(operator);
        IOwnable2Step(address(bond)).acceptOwnership(); // see IOwnable2Step NatSpec
        IAgentRole(address(bond)).addAgent(address(desk));
        bond.unpause(); // T-REX tokens always deploy paused; operator is a token agent
        vm.stopPrank();

        // ── Ecosystem wiring: the operating org may call the desk ────────────────
        orgId = new MockOnchainId();
        ecosystemKycIssuer = new MockClaimIssuer();
        ecosystemAmlIssuer = new MockClaimIssuer();

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), COUNTRY_DE);
        bytes32[] memory aliceRoles = new bytes32[](1);
        aliceRoles[0] = keccak256("TREASURY");
        orgRegistry.addMember(address(orgId), alice, aliceRoles, "");
        permissions.grantToOrg(address(orgId), issue_);
        permissions.grantToOrg(address(orgId), payCoupon_);
        permissions.grantToOrg(address(orgId), redeem_);
        permissions.grantToOrg(address(orgId), pause_);

        uint256[] memory kycTopic = new uint256[](1);
        kycTopic[0] = TOPIC_KYC;
        tir.addTrustedIssuer(address(ecosystemKycIssuer), kycTopic);
        uint256[] memory amlTopic = new uint256[](1);
        amlTopic[0] = TOPIC_AML;
        tir.addTrustedIssuer(address(ecosystemAmlIssuer), amlTopic);
        vm.stopPrank();

        orgId.addClaim(TOPIC_KYC, address(ecosystemKycIssuer), hex"01", hex"02");
        orgId.addClaim(TOPIC_AML, address(ecosystemAmlIssuer), hex"01", hex"02");
    }

    // ── subscribe (primary-market DvP) ─────────────────────────────────────

    function test_subscribe_transfersPaymentAndMintsAtomically() public {
        uint256 investorCashBefore = stable.balanceOf(investor1);
        uint256 treasuryCashBefore = stable.balanceOf(issuerTreasury);

        vm.prank(alice);
        desk.subscribe(investor1, 1000);

        assertEq(bond.balanceOf(investor1), 1000);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);
        assertEq(stable.balanceOf(investor1), investorCashBefore - 1000 * PRICE_PER_UNIT);
        assertEq(stable.balanceOf(issuerTreasury), treasuryCashBefore + 1000 * PRICE_PER_UNIT);
    }

    function test_subscribe_revertsWithoutInvestorAllowance() public {
        vm.prank(investor1);
        stable.approve(address(desk), 0);

        vm.prank(alice);
        vm.expectRevert();
        desk.subscribe(investor1, 1000);

        // DvP is atomic: no bond units without the cash leg.
        assertEq(bond.balanceOf(investor1), 0);
    }

    /// @notice `subscribeWithPermit` needs no prior `approve` transaction at all — a signed
    ///         EIP-2612 permit alone grants the allowance and the atomic subscribe follows.
    function test_subscribeWithPermit_succeedsWithoutPriorApproval() public {
        (, uint256 investor1Key) = makeAddrAndKey("investor1"); // deterministic, same key as setUp's wallet
        vm.prank(investor1);
        stable.approve(address(desk), 0); // prove no standing allowance is needed

        uint256 cost = 1000 * PRICE_PER_UNIT;
        uint256 deadline = block.timestamp + 1 hours;
        bytes32 PERMIT_TYPEHASH =
            keccak256("Permit(address owner,address spender,uint256 value,uint256 nonce,uint256 deadline)");
        bytes32 structHash = keccak256(
            abi.encode(PERMIT_TYPEHASH, investor1, address(desk), cost, stable.nonces(investor1), deadline)
        );
        bytes32 digest = keccak256(abi.encodePacked("\x19\x01", stable.DOMAIN_SEPARATOR(), structHash));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(investor1Key, digest);

        vm.prank(alice);
        desk.subscribeWithPermit(investor1, 1000, deadline, v, r, s);

        assertEq(bond.balanceOf(investor1), 1000);
        assertEq(stable.allowance(investor1, address(desk)), 0, "permit allowance fully consumed by subscribe");
    }

    function test_subscribe_revertsForUnboundWallet() public {
        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, issue_));
        desk.subscribe(investor1, 1000);
    }

    function test_subscribe_revertsWithoutOrgKycClaim() public {
        ecosystemKycIssuer.setValid(false);

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, alice, TOPIC_KYC));
        desk.subscribe(investor1, 1000);
    }

    /// @notice Ecosystem gating alone is not enough — the T-REX layer independently
    ///         rejects minting to a wallet with no registered ONCHAINID at all, and the
    ///         same-transaction DvP then rolls the already-executed cash leg back with it.
    function test_subscribe_revertsWhenInvestorNotVerifiedOnTrex() public {
        address strangerInvestor = makeAddr("noIdentity");
        stable.mint(strangerInvestor, 1_000_000e6);
        vm.prank(strangerInvestor);
        stable.approve(address(desk), type(uint256).max);

        vm.prank(alice);
        vm.expectRevert();
        desk.subscribe(strangerInvestor, 1000);

        assertEq(stable.balanceOf(strangerInvestor), 1_000_000e6, "cash leg must roll back with the failed mint");
    }

    // ── payCoupon ──────────────────────────────────────────────────────────

    function test_payCoupon_paysStablecoinAndSkipsZeroBalances() public {
        vm.startPrank(alice);
        desk.subscribe(investor1, 1000);
        desk.subscribe(investor3, 2000);
        vm.stopPrank();

        uint256 coupon1 = (uint256(1000) * PRICE_PER_UNIT * COUPON_BPS) / 10_000;
        uint256 coupon3 = (uint256(2000) * PRICE_PER_UNIT * COUPON_BPS) / 10_000;
        uint256 cash1Before = stable.balanceOf(investor1);
        uint256 cash3Before = stable.balanceOf(investor3);
        uint256 cash4Before = stable.balanceOf(investor4);

        address[] memory holders = new address[](3);
        holders[0] = investor1;
        holders[1] = investor3;
        holders[2] = investor4; // zero balance — must be skipped, no event, no payout

        vm.warp(block.timestamp + COUPON_INTERVAL);

        vm.expectEmit(true, true, false, true, address(desk));
        emit EwpgBondDesk.CouponPaid(1, investor1, coupon1);
        vm.expectEmit(true, true, false, true, address(desk));
        emit EwpgBondDesk.CouponPaid(1, investor3, coupon3);

        vm.prank(alice);
        uint256 period = desk.payCoupon(holders);

        assertEq(period, 1);
        assertEq(desk.couponPeriod(), 1);
        assertEq(stable.balanceOf(investor1), cash1Before + coupon1);
        assertEq(stable.balanceOf(investor3), cash3Before + coupon3);
        assertEq(stable.balanceOf(investor4), cash4Before);
    }

    function test_payCoupon_revertsBeforeFirstPeriodIsDue() public {
        vm.prank(alice);
        desk.subscribe(investor1, 1000);

        address[] memory holders = new address[](1);
        holders[0] = investor1;
        uint256 due = desk.nextCouponDue();

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(EwpgBondDesk.NoCouponPeriodOpen.selector, due));
        desk.payCoupon(holders);
    }

    /// @notice Regression test: re-running payCoupon in the same period must never pay a
    ///         holder twice — the original desk incremented the period on every call and
    ///         re-emitted fresh obligations, which an off-chain paying agent would have
    ///         settled again.
    function test_payCoupon_isIdempotentPerPeriodPerHolder() public {
        vm.prank(alice);
        desk.subscribe(investor1, 1000);

        address[] memory holders = new address[](1);
        holders[0] = investor1;

        vm.warp(block.timestamp + COUPON_INTERVAL);
        uint256 coupon = (uint256(1000) * PRICE_PER_UNIT * COUPON_BPS) / 10_000;
        uint256 cashBefore = stable.balanceOf(investor1);

        vm.startPrank(alice);
        uint256 firstCall = desk.payCoupon(holders);
        uint256 secondCall = desk.payCoupon(holders); // same period — must be a no-op
        vm.stopPrank();

        assertEq(firstCall, 1);
        assertEq(secondCall, 1, "period must not advance before the next interval is due");
        assertEq(stable.balanceOf(investor1), cashBefore + coupon, "holder must be paid exactly once");

        // The next period opens only after another interval and pays again.
        vm.warp(block.timestamp + COUPON_INTERVAL);
        vm.prank(alice);
        uint256 thirdCall = desk.payCoupon(holders);
        assertEq(thirdCall, 2);
        assertEq(stable.balanceOf(investor1), cashBefore + 2 * coupon);
    }

    function test_payCoupon_revertsWithoutOrgKycClaim() public {
        ecosystemKycIssuer.setValid(false);

        address[] memory holders = new address[](1);
        holders[0] = investor1;

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, alice, TOPIC_KYC));
        desk.payCoupon(holders);
    }

    /// @notice Regression test for finding #5 (Phase 8): the original desk paid from a
    ///         live {IERC3643-balanceOf} read, so a holder paid in one call could transfer
    ///         their units to a second address and collect the same period's coupon again
    ///         there once that address appeared in a later call. The record-date snapshot
    ///         must prevent this: investor2 receiving investor1's already-paid units within
    ///         the same period, in a call after the period opened, must be skipped entirely.
    function test_payCoupon_recordDateSnapshot_preventsDoublePayAcrossMidPeriodTransfer() public {
        vm.prank(alice);
        desk.subscribe(investor1, 1000);

        address[] memory openingHolders = new address[](1);
        openingHolders[0] = investor1;

        vm.warp(block.timestamp + COUPON_INTERVAL);
        uint256 coupon = (uint256(1000) * PRICE_PER_UNIT * COUPON_BPS) / 10_000;
        uint256 investor1CashBefore = stable.balanceOf(investor1);
        uint256 investor2CashBefore = stable.balanceOf(investor2);

        vm.prank(alice);
        desk.payCoupon(openingHolders); // opens period 1, pays investor1 from the snapshot

        // investor1 moves the very units already paid for to investor2, still within period 1.
        vm.prank(investor1);
        bond.transfer(investor2, 1000);

        address[] memory stragglerHolders = new address[](1);
        stragglerHolders[0] = investor2;
        vm.prank(alice);
        uint256 period = desk.payCoupon(stragglerHolders);

        assertEq(period, 1);
        assertEq(stable.balanceOf(investor1), investor1CashBefore + coupon, "investor1 paid exactly once");
        assertEq(stable.balanceOf(investor2), investor2CashBefore,
            "investor2 must NOT be paid - not part of the period-open snapshot, their balance already reflects the transfer");
        assertFalse(desk.couponPaid(period, investor2));
    }

    /// @notice The record-date snapshot is taken from whichever call actually opens the
    ///         period — every holder in *that* call is eligible, even if there are many.
    function test_payCoupon_snapshotsEveryHolderInTheOpeningCall() public {
        vm.startPrank(alice);
        desk.subscribe(investor1, 1000);
        desk.subscribe(investor3, 2000);
        vm.stopPrank();

        address[] memory holders = new address[](2);
        holders[0] = investor1;
        holders[1] = investor3;

        vm.warp(block.timestamp + COUPON_INTERVAL);
        uint256 coupon1 = (uint256(1000) * PRICE_PER_UNIT * COUPON_BPS) / 10_000;
        uint256 coupon3 = (uint256(2000) * PRICE_PER_UNIT * COUPON_BPS) / 10_000;
        uint256 cash1Before = stable.balanceOf(investor1);
        uint256 cash3Before = stable.balanceOf(investor3);

        vm.prank(alice);
        desk.payCoupon(holders);

        assertEq(stable.balanceOf(investor1), cash1Before + coupon1);
        assertEq(stable.balanceOf(investor3), cash3Before + coupon3);
        assertTrue(desk.periodBalanceSnapshotted(1, investor1));
        assertTrue(desk.periodBalanceSnapshotted(1, investor3));
    }

    // ── pause (finding #2) ────────────────────────────────────────────────

    function test_pause_blocksSubscribePayCouponAndRedeem() public {
        vm.prank(alice);
        desk.pause();
        assertTrue(desk.paused());

        vm.startPrank(alice);
        vm.expectRevert(EwpgBondDesk.DeskIsPaused.selector);
        desk.subscribe(investor1, 1000);

        address[] memory holders = new address[](1);
        holders[0] = investor1;
        vm.expectRevert(EwpgBondDesk.DeskIsPaused.selector);
        desk.payCoupon(holders);

        vm.expectRevert(EwpgBondDesk.DeskIsPaused.selector);
        desk.redeem(investor1);
        vm.stopPrank();
    }

    function test_pause_revertsForWalletWithoutPausePermission() public {
        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, pause_));
        desk.pause();
    }

    function test_unpause_resumesOperation() public {
        vm.startPrank(alice);
        desk.pause();
        desk.unpause();
        vm.stopPrank();

        assertFalse(desk.paused());
        vm.prank(alice);
        desk.subscribe(investor1, 1000); // succeeds — no revert
        assertEq(bond.balanceOf(investor1), 1000);
    }

    // ── redeem ─────────────────────────────────────────────────────────────

    function test_redeem_revertsBeforeMaturity() public {
        vm.prank(alice);
        desk.subscribe(investor1, 1000);

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(EwpgBondDesk.BondNotMatured.selector, maturity));
        desk.redeem(investor1);
    }

    function test_redeem_paysPrincipalAtMaturityAndDecrementsInvestorCount() public {
        vm.prank(alice);
        desk.subscribe(investor1, 1000);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);

        uint256 cashBefore = stable.balanceOf(investor1);
        uint256 principal = 1000 * PRICE_PER_UNIT;

        vm.warp(maturity);
        vm.prank(alice);
        desk.redeem(investor1);

        assertEq(bond.balanceOf(investor1), 0);
        assertTrue(desk.redeemed(investor1));
        assertEq(complianceModule.getInvestorCount(complianceAddr), 0);
        assertEq(stable.balanceOf(investor1), cashBefore + principal, "principal must be paid out on redemption");
    }

    function test_redeem_revertsOnDoubleRedeem() public {
        vm.prank(alice);
        desk.subscribe(investor1, 1000);
        vm.warp(maturity);

        vm.startPrank(alice);
        desk.redeem(investor1);
        vm.expectRevert(abi.encodeWithSelector(EwpgBondDesk.AlreadyRedeemed.selector, investor1));
        desk.redeem(investor1);
        vm.stopPrank();
    }

    function test_redeem_revertsWithoutOrgAmlClaim() public {
        vm.prank(alice);
        desk.subscribe(investor1, 1000);
        vm.warp(maturity);

        ecosystemAmlIssuer.setValid(false);

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, alice, TOPIC_AML));
        desk.redeem(investor1);
    }

    // ── EwpgComplianceModule fixes ─────────────────────────────────────────

    /// @notice Regression test for the moduleCheck fix: blocked countries must actually
    ///         be enforced, not just recorded.
    function test_compliance_blocksAndUnblocksMintToBlockedCountry() public {
        vm.startPrank(operator);
        identityRegistry.updateCountry(investor2, COUNTRY_BLOCKED);
        complianceModule.blockCountry(complianceAddr, COUNTRY_BLOCKED);
        vm.stopPrank();

        assertTrue(complianceModule.isCountryBlocked(complianceAddr, COUNTRY_BLOCKED));
        assertFalse(complianceModule.moduleCheck(address(0), investor2, 500, complianceAddr));

        vm.prank(alice);
        vm.expectRevert();
        desk.subscribe(investor2, 500);

        vm.prank(operator);
        complianceModule.unblockCountry(complianceAddr, COUNTRY_BLOCKED);
        assertTrue(complianceModule.moduleCheck(address(0), investor2, 500, complianceAddr));

        vm.prank(alice);
        desk.subscribe(investor2, 500);
        assertEq(bond.balanceOf(investor2), 500);
    }

    /// @notice Regression test for the investor-count fix: topping up an existing
    ///         investor must not double-count, and count must track transfers, not just
    ///         mint/burn.
    function test_investorCount_tracksTransitionsAcrossMintTransferBurn() public {
        vm.startPrank(alice);
        desk.subscribe(investor1, 1000);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);

        // Topping up an existing investor must not double-count (the original bug).
        desk.subscribe(investor1, 500);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);

        desk.subscribe(investor3, 2000);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 2);
        vm.stopPrank();

        // investor1 transfers its entire position to investor3 (already an investor):
        // investor1 drops out, investor3 stays counted once.
        vm.prank(investor1);
        bond.transfer(investor3, 1500);
        assertEq(bond.balanceOf(investor1), 0);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);

        // A brand-new recipient receiving a partial transfer-in becomes a new investor
        // (the original bug only ever counted mints, never transfers-in).
        vm.prank(investor3);
        bond.transfer(investor4, 100);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 2);
    }

    // ── EwpgComplianceModule: nominee/omnibus pool exemption ────────────────

    /// @notice A contract address flagged as a nominee pool is exempt from maxBalance and
    ///         maxInvestors — the whole point, since a pool nets many LPs behind one
    ///         address. Unflagging restores the caps for that same address.
    function test_nomineePool_bypassesMaxBalanceAndMaxInvestors() public {
        vm.startPrank(operator);
        complianceModule.setMaxInvestors(complianceAddr, 1);
        complianceModule.setMaxBalance(complianceAddr, 100);
        vm.stopPrank();

        // Fill the single investor slot with investor1.
        vm.prank(alice);
        desk.subscribe(investor1, 50);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);

        // A second, non-nominee address is rejected: the investor cap is already full.
        assertFalse(complianceModule.moduleCheck(address(0), investor2, 10, complianceAddr));

        // `desk` is a real deployed contract in this fixture — flag it as a nominee pool.
        vm.prank(operator);
        complianceModule.setNomineePool(complianceAddr, address(desk), true);
        assertTrue(complianceModule.moduleCheck(address(0), address(desk), 10_000, complianceAddr));

        // Unflagging restores both caps for that same address.
        vm.prank(operator);
        complianceModule.setNomineePool(complianceAddr, address(desk), false);
        assertFalse(complianceModule.moduleCheck(address(0), address(desk), 10_000, complianceAddr));
    }

    /// @notice Flagging an EOA as a nominee pool is a no-op — only a contract address can
    ///         be treated as one.
    function test_nomineePool_flagIsNoOpForEoa() public {
        vm.startPrank(operator);
        complianceModule.setMaxBalance(complianceAddr, 100);
        complianceModule.setNomineePool(complianceAddr, investor2, true); // investor2 is an EOA
        vm.stopPrank();

        assertFalse(complianceModule.moduleCheck(address(0), investor2, 10_000, complianceAddr));
    }

    /// @notice The nominee exemption never overrides the country block — a nominee pool
    ///         operator must still not be domiciled in a blocked jurisdiction.
    function test_nomineePool_stillBlockedForBlockedCountry() public {
        vm.startPrank(operator);
        // Register `desk`'s address with a country so investorCountry() resolves to
        // something other than the unset default; reusing an existing onchain-id contract
        // is fine here since moduleCheck only reads the registered country, not claims.
        identityRegistry.registerIdentity(address(desk), IIdentity(investor4Identity), COUNTRY_BLOCKED);
        complianceModule.blockCountry(complianceAddr, COUNTRY_BLOCKED);
        complianceModule.setNomineePool(complianceAddr, address(desk), true);
        vm.stopPrank();

        assertFalse(complianceModule.moduleCheck(address(0), address(desk), 10_000, complianceAddr));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    function _createVerifiedInvestor(string memory label) private returns (address wallet, address identity) {
        (wallet,) = makeAddrAndKey(label);
        vm.prank(operator);
        identity = trex.idFactory.createIdentity(wallet, label);
        _addClaim(identity, wallet, TOPIC_KYC);
        _addClaim(identity, wallet, TOPIC_AML);
    }

    /// @dev Signs a real ECDSA claim as {claimIssuer} and adds it to the investor's
    ///      ONCHAINID, replicating exactly what {ClaimIssuer.isClaimValid} verifies.
    function _addClaim(address identity, address wallet, uint256 topic) private {
        bytes memory data = "";
        bytes32 dataHash = keccak256(abi.encode(identity, topic, data));
        bytes32 prefixedHash = keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", dataHash));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(issuerKey, prefixedHash);
        bytes memory signature = abi.encodePacked(r, s, v);

        vm.prank(wallet);
        IIdentity(identity).addClaim(topic, 1, address(claimIssuer), signature, data, "");
    }
}
