// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "@erc3643/ERC-3643/IERC3643.sol";
import "@erc3643/ERC-3643/IERC3643IdentityRegistry.sol";
import "@erc3643/factory/ITREXFactory.sol";
import "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import "@onchain-id/solidity/contracts/interface/IIdentity.sol";
import "../helpers/TrexSuiteDeployer.sol";

// Selective imports: the ecosystem declares its own minimal IIdentity/IClaimIssuer that collide
// by name with the onchain-id ones above (same reason as in EwpgBondDesk.t.sol).
import {EcosystemTrustedIssuersRegistry} from "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import {OrgRegistry} from "../../src/ecosystem/OrgRegistry.sol";
import {PermissionOracle} from "../../src/ecosystem/PermissionOracle.sol";
import {PermissionRegistry} from "../../src/ecosystem/PermissionRegistry.sol";
import {MockStablecoin} from "../../src/examples/MockStablecoin.sol";
import {MockClaimIssuer} from "../ecosystem/mocks/MockClaimIssuer.sol";
import {MockOnchainId} from "../ecosystem/mocks/MockOnchainId.sol";

interface IOwnable2StepRepo {
    function acceptOwnership() external;
}

/// @dev The T-REX sources are pinned to solc =0.8.30 while {EwpgRepoMarket} needs ^0.8.36, so
///      the two cannot share a compilation unit: the market is deployed from its build artifact
///      with `vm.deployCode` and driven through this local interface.
interface IEwpgRepoMarket {
    function BORROW() external view returns (bytes32);
    function supply(uint256 amount) external returns (uint256);
    function pledgeAndBorrow(uint256 collateralAmount, uint256 borrowAmount) external;
    function repay(uint256 repayAmount) external returns (uint256);
    function repayDebtOnly(uint256 repayAmount) external returns (uint256);
    function claimCollateral() external returns (uint256);
    function liquidate(address borrower, uint256 maxRepayAmount) external returns (uint256, uint256);
    function debtOf(address borrower) external view returns (uint256);
    function positions(address borrower) external view returns (uint256 collateralAmount, uint256 scaledDebt);
    function surplusOf(address borrower) external view returns (uint256);
    function claimLiquidationSurplus() external returns (uint256);
}

/// @dev Minimal settable price feed with the `IRepoOracle` shape (that interface file is ^0.8.36).
contract SettableRepoOracle {
    mapping(address => uint256) public prices;
    address public immutable quoteToken;

    constructor(address quoteToken_) {
        quoteToken = quoteToken_;
    }

    function setPrice(address asset, uint256 p) external {
        prices[asset] = p;
    }

    function price(address asset) external view returns (uint256, uint256) {
        return (prices[asset], block.timestamp);
    }

    /// @dev 15%: the 80% LLTV × 1.05 bonus below must fit under 1 − maxDeviation.
    function maxDeviationBps() external pure returns (uint256) {
        return 1500;
    }
}

/// @notice {EwpgRepoMarket} with a real T-REX (ERC-3643) collateral token, covering a borrower
///         whose collateral-token eligibility lapses while a loan is open (wallet freeze, token
///         pause). T-REX `transfer` reverts for a frozen or unverified recipient, even for a
///         zero amount, so every path that pushes collateral to the borrower fails for them.
///         `repayDebtOnly`, `claimCollateral` and the credited liquidation residual keep
///         de-risking possible in that state.
contract EwpgRepoMarketTrexCollateralTest is Test {
    uint256 internal constant TOPIC_KYC = 1;
    uint16 internal constant COUNTRY_DE = 276;
    uint256 internal constant PRICE_PER_UNIT = 100e6;
    string internal constant SALT = "repo-collateral-bond";

    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle ecosystemOracle;

    TrexDeployment trex;
    ClaimIssuer claimIssuer;
    uint256 issuerKey;

    IERC3643 bond;
    IERC3643IdentityRegistry identityRegistry;
    MockStablecoin loanToken;
    SettableRepoOracle navOracle;
    IEwpgRepoMarket market;

    address operator = address(0x1);
    address lender = address(0x11);
    address alice;
    address liquidator;

    function setUp() public {
        // ── Ecosystem: alice may borrow ─────────────────────────────────────────
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        ecosystemOracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        loanToken = new MockStablecoin("AllUnity Euro", "AUEUR", 6);
        navOracle = new SettableRepoOracle(address(loanToken));

        // ── T-REX collateral token (KYC topic only, no compliance modules) ──────
        trex = TrexSuiteDeployer.deploy(operator);
        (address issuerSigner, uint256 key) = makeAddrAndKey("repoClaimIssuer");
        issuerKey = key;
        claimIssuer = new ClaimIssuer(issuerSigner);

        address[] memory agents = new address[](1);
        agents[0] = operator;
        ITREXFactory.TokenDetails memory tokenDetails = ITREXFactory.TokenDetails({
            owner: operator,
            name: "Repo Collateral Bond",
            symbol: "RCB",
            decimals: 0,
            irs: address(0),
            ONCHAINID: address(0),
            irAgents: agents,
            tokenAgents: agents,
            complianceModules: new address[](0),
            complianceSettings: new bytes[](0)
        });
        uint256[] memory topics = new uint256[](1);
        topics[0] = TOPIC_KYC;
        address[] memory issuers = new address[](1);
        issuers[0] = address(claimIssuer);
        uint256[][] memory issuerClaims = new uint256[][](1);
        issuerClaims[0] = topics;
        vm.prank(operator);
        bond = IERC3643(
            trex.factory.deployEwpgSuite(
                keccak256(bytes(SALT)),
                SALT,
                tokenDetails,
                ITREXFactory.ClaimDetails({claimTopics: topics, issuers: issuers, issuerClaims: issuerClaims})
            )
        );
        identityRegistry = bond.identityRegistry();

        // Static-field MarketParams struct: its ABI encoding is the flat field list.
        market = IEwpgRepoMarket(
            vm.deployCode(
                "EwpgRepoMarket.sol:EwpgRepoMarket",
                abi.encode(
                    address(ecosystemOracle),
                    address(new MockOnchainId()), // operatorOrg
                    operator, // treasury
                    address(loanToken),
                    address(bond),
                    address(navOracle),
                    7000, // maxLtvBps
                    8000, // lltvBps
                    500, // liquidationBonusBps
                    0.02e18,
                    0.18e18,
                    0, // no staleness check
                    0
                )
            )
        );

        // alice, the liquidator and the market itself must be verified T-REX holders.
        alice = _registerVerified("alice");
        liquidator = _registerVerified("liquidator");
        _registerVerifiedAddress(address(market), "market");

        vm.startPrank(operator);
        IOwnable2StepRepo(address(bond)).acceptOwnership();
        bond.unpause(); // T-REX tokens deploy paused
        bond.mint(alice, 1_000);

        MockOnchainId orgId = new MockOnchainId();
        MockClaimIssuer kycIssuer = new MockClaimIssuer();
        orgRegistry.registerOrg(address(orgId), COUNTRY_DE);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(orgId), alice, roles, "");
        permissions.grantToOrg(address(orgId), market.BORROW());
        tir.addTrustedIssuer(address(kycIssuer), topics);
        vm.stopPrank();
        orgId.addClaim(TOPIC_KYC, address(kycIssuer), hex"01", hex"02");

        navOracle.setPrice(address(bond), PRICE_PER_UNIT);

        loanToken.mint(lender, 1_000_000e6);
        loanToken.mint(liquidator, 1_000_000e6);
        loanToken.mint(alice, 100_000e6); // enough to repay with interest
        vm.prank(lender);
        loanToken.approve(address(market), type(uint256).max);
        vm.prank(liquidator);
        loanToken.approve(address(market), type(uint256).max);
        vm.startPrank(alice);
        loanToken.approve(address(market), type(uint256).max);
        bond.approve(address(market), type(uint256).max);
        vm.stopPrank();

        vm.prank(lender);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);
    }

    // ── repay is blocked for an ineligible borrower (documents the original defect) ──

    function test_repay_revertsWhileBorrowerFrozen() public {
        _freezeAlice(true);
        vm.prank(alice);
        vm.expectRevert();
        market.repay(1_000e6);
    }

    // ── repayDebtOnly ────────────────────────────────────────────────────────

    function test_repayDebtOnly_succeedsWhileBorrowerFrozen() public {
        _freezeAlice(true);
        uint256 debt = market.debtOf(alice);

        vm.prank(alice);
        uint256 paid = market.repayDebtOnly(debt);

        assertEq(paid, debt);
        assertEq(market.debtOf(alice), 0);
        (uint256 collateral, uint256 scaledDebt) = market.positions(alice);
        assertEq(collateral, 100, "collateral stays pledged");
        assertEq(scaledDebt, 0);
        assertEq(bond.balanceOf(address(market)), 100);
    }

    function test_repayDebtOnly_succeedsWhileCollateralTokenPaused() public {
        vm.prank(operator);
        bond.pause();

        vm.prank(alice);
        market.repayDebtOnly(3_000e6);

        assertApproxEqAbs(market.debtOf(alice), 4_000e6, 1);
    }

    // ── claimCollateral ──────────────────────────────────────────────────────

    function test_claimCollateral_afterUnfreeze_releasesAllCollateral() public {
        _freezeAlice(true);
        vm.prank(alice);
        market.repayDebtOnly(type(uint256).max);

        vm.prank(alice);
        vm.expectRevert();
        market.claimCollateral(); // still frozen: the token refuses delivery

        _freezeAlice(false);
        vm.prank(alice);
        uint256 claimed = market.claimCollateral();

        assertEq(claimed, 100);
        assertEq(bond.balanceOf(alice), 1_000);
        assertEq(bond.balanceOf(address(market)), 0);
        (uint256 collateral,) = market.positions(alice);
        assertEq(collateral, 0);
    }

    // ── liquidation with a frozen borrower ───────────────────────────────────

    /// @dev Price 80: HF = 100*80*0.8/7000 = 0.914 < 0.95, so one call closes the full debt;
    ///      units = ceil(7000*1.05/80) = 92, leaving an 8-unit residual plus a cash surplus
    ///      (92*80/1.05 − 7000). Neither is pushed to the frozen borrower, which would revert
    ///      the whole liquidation.
    function test_liquidate_fullClose_doesNotRevertForFrozenBorrower() public {
        navOracle.setPrice(address(bond), 80e6);
        _freezeAlice(true);

        uint256 debt = market.debtOf(alice);
        vm.prank(liquidator);
        (uint256 debtRepaid, uint256 seized) = market.liquidate(alice, debt);

        assertEq(debtRepaid, debt);
        assertEq(seized, 92);
        assertEq(bond.balanceOf(liquidator), 92);
        (uint256 collateral, uint256 scaledDebt) = market.positions(alice);
        assertEq(scaledDebt, 0);
        assertEq(collateral, 8, "residual credited to the position");
        assertEq(bond.balanceOf(address(market)), 8);
        uint256 surplus = market.surplusOf(alice);
        assertEq(surplus, (92 * uint256(80e6) * 10_000 + 10_499) / 10_500 - debt, "surplus credited, not pushed");

        _freezeAlice(false);
        vm.prank(alice);
        assertEq(market.claimCollateral(), 8);
        assertEq(bond.balanceOf(alice), 908);
        vm.prank(alice);
        assertEq(market.claimLiquidationSurplus(), surplus);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    function _freezeAlice(bool frozen) private {
        vm.prank(operator);
        bond.setAddressFrozen(alice, frozen);
    }

    function _registerVerified(string memory label) private returns (address wallet) {
        wallet = makeAddr(label);
        _registerVerifiedAddress(wallet, label);
    }

    function _registerVerifiedAddress(address wallet, string memory label) private {
        vm.prank(operator);
        address identity = trex.idFactory.createIdentity(wallet, label);

        bytes memory data = "";
        bytes32 dataHash = keccak256(abi.encode(identity, TOPIC_KYC, data));
        bytes32 prefixedHash = keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", dataHash));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(issuerKey, prefixedHash);
        vm.prank(wallet);
        IIdentity(identity).addClaim(TOPIC_KYC, 1, address(claimIssuer), abi.encodePacked(r, s, v), data, "");

        vm.prank(operator);
        identityRegistry.registerIdentity(wallet, IIdentity(identity), COUNTRY_DE);
    }
}
