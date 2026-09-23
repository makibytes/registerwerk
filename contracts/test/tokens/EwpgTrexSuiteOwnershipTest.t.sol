// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "@erc3643/ERC-3643/IERC3643.sol";
import "@erc3643/factory/ITREXFactory.sol";

import "../helpers/TrexSuiteDeployer.sol";

interface ISuiteOwnable {
    function owner() external view returns (address);
    function acceptOwnership() external;
}

interface ITirAdmin {
    function addTrustedIssuer(address issuer, uint256[] calldata topics) external;
}

interface ICtrAdmin {
    function addClaimTopic(uint256 topic) external;
}

/// @notice T1-20: pins the premise behind the backend's accept-ownership step. After
///         `deployEwpgSuite`, T-REX's `OwnableOnceNext2StepUpgradeable` leaves the registry
///         wallet only the *pending* owner of token, IR, TIR, CTR and compliance, so owner-only
///         registry calls revert until each contract's ownership is accepted.
contract EwpgTrexSuiteOwnershipTest is Test {
    address internal operator = address(0x1);
    address[5] internal suite; // token, ir, tir, ctr, compliance

    function setUp() public {
        TrexDeployment memory trex = TrexSuiteDeployer.deploy(operator);
        address[] memory agents = new address[](1);
        agents[0] = operator;
        ITREXFactory.TokenDetails memory details = ITREXFactory.TokenDetails({
            owner: operator,
            name: "Ownership Bond",
            symbol: "OWNB",
            decimals: 0,
            irs: address(0),
            ONCHAINID: address(0),
            irAgents: agents,
            tokenAgents: agents,
            complianceModules: new address[](0),
            complianceSettings: new bytes[](0)
        });
        uint256[] memory topics = new uint256[](1);
        topics[0] = 1;
        address[] memory issuers = new address[](1);
        issuers[0] = address(0x999);
        uint256[][] memory issuerClaims = new uint256[][](1);
        issuerClaims[0] = topics;
        vm.prank(operator);
        address token = trex.factory.deployEwpgSuite(
            keccak256("ownership-test"),
            "ownership-test",
            details,
            ITREXFactory.ClaimDetails({claimTopics: topics, issuers: issuers, issuerClaims: issuerClaims})
        );
        IERC3643IdentityRegistry ir = IERC3643(token).identityRegistry();
        suite = [
            token,
            address(ir),
            address(ir.issuersRegistry()),
            address(ir.topicsRegistry()),
            address(IERC3643(token).compliance())
        ];
    }

    function test_allSuiteContractsArePendingUntilAccepted() public {
        for (uint256 i = 0; i < suite.length; i++) {
            assertTrue(ISuiteOwnable(suite[i]).owner() != operator, "owner before accept");
            vm.prank(operator);
            ISuiteOwnable(suite[i]).acceptOwnership();
            assertEq(ISuiteOwnable(suite[i]).owner(), operator, "owner after accept");
        }
    }

    function test_ownerOnlyRegistryCallsRevertUntilAccepted() public {
        uint256[] memory topics = new uint256[](1);
        topics[0] = 2;

        vm.startPrank(operator);
        vm.expectRevert();
        ITirAdmin(suite[2]).addTrustedIssuer(address(0x777), topics);
        vm.expectRevert();
        ICtrAdmin(suite[3]).addClaimTopic(2);

        ISuiteOwnable(suite[2]).acceptOwnership();
        ISuiteOwnable(suite[3]).acceptOwnership();
        ITirAdmin(suite[2]).addTrustedIssuer(address(0x777), topics);
        ICtrAdmin(suite[3]).addClaimTopic(2);
        vm.stopPrank();
    }
}
