// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/tokens/EwpgERC20.sol";
import "../../src/tokens/EwpgERC721.sol";
import "../../src/tokens/EwpgERC3525.sol";

/// @dev A registry that is also a holder, so a forced operation and a normal transfer happen inside ONE
///      transaction. (Foundry clears transient storage between the test's own top-level calls, so a flag
///      that a forced operation failed to clear would go unnoticed if the test made the calls itself.)
contract ComplianceProbe {
    EwpgERC20 public erc20;
    EwpgERC721 public nft;
    bool public erc20ComplianceBack;
    bool public erc721ComplianceBack;

    function run(address stranger) external {
        erc20 = new EwpgERC20("Unit", "UNIT", address(this), keccak256("probe-20"));
        erc20.whitelist(address(this));
        erc20.mint(address(this), 100);
        erc20.forcedTransfer(address(this), stranger, 10, "BaFin Az."); // stranger is not whitelisted
        try erc20.transfer(stranger, 1) {
            erc20ComplianceBack = false;
        } catch {
            erc20ComplianceBack = true;
        }

        nft = new EwpgERC721("NFT", "NFT", address(this), keccak256("probe-721"));
        nft.whitelist(address(this));
        nft.mint(address(this), 1);
        nft.mint(address(this), 2);
        nft.forcedTransfer(address(this), stranger, 1, "BaFin Az.");
        try nft.transferFrom(address(this), stranger, 2) {
            erc721ComplianceBack = false;
        } catch {
            erc721ComplianceBack = true;
        }
    }
}

/// @notice The registry's forced operations are regulatory acts, and their events are the audit
///         trail. A forced approval or burn of a whole token must name the token's real owner (the
///         caller cannot make the event say otherwise), and lifting the compliance hooks for one
///         forced operation must never leave them lifted for the next call.
contract ForceOperationAuditTest is Test {
    EwpgERC20 erc20;
    EwpgERC721 nft;
    EwpgERC3525 sft;

    address registry = address(0x1);
    address alice = address(0x2);
    address bob = address(0x3);
    address mallory = address(0x4);

    function setUp() public {
        vm.startPrank(registry);
        erc20 = new EwpgERC20("Unit", "UNIT", registry, keccak256("erc20"));
        nft = new EwpgERC721("NFT", "NFT", registry, keccak256("nft"));
        sft = new EwpgERC3525("SFT", "SFT", registry, keccak256("sft"));
        erc20.whitelist(alice);
        erc20.whitelist(bob);
        nft.whitelist(alice);
        nft.whitelist(bob);
        sft.whitelist(alice);
        sft.whitelist(bob);
        erc20.mint(alice, 100);
        nft.mint(alice, 1);
        sft.mint(alice, 7, 500);
        vm.stopPrank();
    }

    // ── ERC-721 ──────────────────────────────────────────────────────────────

    function test_erc721_forcedApprove_namesTheRealOwner() public {
        vm.prank(registry);
        vm.expectEmit(true, true, false, true);
        emit IEwpgAdminControls.ForcedApprove(alice, bob, 1, "BaFin Az. 1");
        nft.forcedApprove(alice, bob, 1, "BaFin Az. 1");
        assertEq(nft.getApproved(1), bob);
    }

    function test_erc721_forcedApprove_wrongOwner_reverts() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC721: not the token owner");
        nft.forcedApprove(mallory, bob, 1, "BaFin Az. 1");
    }

    function test_erc721_forceBurn_namesTheRealOwner() public {
        vm.prank(registry);
        vm.expectEmit(true, false, false, true);
        emit IEwpgAdminControls.ForceBurned(alice, 1, "BaFin Az. 2");
        nft.forceBurn(alice, 1, "BaFin Az. 2");
        vm.expectRevert();
        nft.ownerOf(1);
    }

    function test_erc721_forceBurn_wrongOwner_revertsAndKeepsTheToken() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC721: not the token owner");
        nft.forceBurn(mallory, 1, "BaFin Az. 2");
        assertEq(nft.ownerOf(1), alice);
    }

    function test_erc721_forceBurn_unknownToken_reverts() public {
        vm.prank(registry);
        vm.expectRevert();
        nft.forceBurn(alice, 99, "BaFin Az. 3");
    }

    // ── ERC-3525 ─────────────────────────────────────────────────────────────

    function test_erc3525_forcedApprove_namesTheRealOwner() public {
        vm.prank(registry);
        sft.forcedApprove(alice, bob, 1, "BaFin Az. 4");
        assertEq(sft.getApproved(1), bob);
    }

    function test_erc3525_forcedApprove_wrongOwner_reverts() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC3525: not the token owner");
        sft.forcedApprove(mallory, bob, 1, "BaFin Az. 4");
    }

    function test_erc3525_forceBurn_wrongOwner_revertsAndKeepsTheToken() public {
        vm.prank(registry);
        vm.expectRevert("EwpgERC3525: not the token owner");
        sft.forceBurn(mallory, 1, "BaFin Az. 5");
        assertEq(sft.ownerOf(1), alice);
        assertEq(sft.balanceOf(1), 500);
    }

    function test_erc3525_forceBurn_ownerBurnsTheWholePosition() public {
        vm.prank(registry);
        sft.forceBurn(alice, 1, "BaFin Az. 5");
        vm.expectRevert();
        sft.ownerOf(1);
    }

    // ── The compliance hooks come back after a forced operation ──────────────

    function test_complianceIsBackAfterAForcedOperationInTheSameTransaction() public {
        ComplianceProbe probe = new ComplianceProbe();
        probe.run(address(0x9));
        assertTrue(probe.erc20ComplianceBack(), "ERC-20 transfer to a non-whitelisted address went through");
        assertTrue(probe.erc721ComplianceBack(), "ERC-721 transfer to a non-whitelisted address went through");
    }
}
