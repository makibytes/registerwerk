// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "../../src/settlement/DvpSettlement.sol";
import "../../src/examples/MockStablecoin.sol";

/// @dev A token that keeps a 1 % fee on every transfer, so the receiver gets less than was sent.
contract FeeOnTransferToken is ERC20 {
    address private constant FEE_SINK = address(0xFEE);

    constructor() ERC20("Fee Token", "FEE") {}

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }

    function _update(address from, address to, uint256 amount) internal override {
        if (from == address(0) || to == address(0)) {
            super._update(from, to, amount);
            return;
        }
        uint256 fee = amount / 100;
        super._update(from, FEE_SINK, fee);
        super._update(from, to, amount - fee);
    }
}

/// @notice Settlement pays the full recorded amount of a leg out of the escrow, so an escrow that
///         arrived short would be topped up from other trades' escrow in the same token. A lock must
///         therefore revert unless the exact amount arrived.
contract DvpSettlementExactEscrowTest is Test {
    DvpSettlement dvp;
    FeeOnTransferToken feeToken;
    MockStablecoin cash;

    address operator = address(0x10);
    address seller = address(0x11);
    address buyer = address(0x22);
    uint64 expiry;

    function setUp() public {
        dvp = new DvpSettlement(operator);
        feeToken = new FeeOnTransferToken();
        cash = new MockStablecoin("USD Coin", "USDC", 6);
        expiry = uint64(block.timestamp + 1 days);

        feeToken.mint(seller, 1_000);
        feeToken.mint(buyer, 100_000e6);
        cash.mint(seller, 100_000e6);
        cash.mint(buyer, 100_000e6);
        vm.startPrank(seller);
        feeToken.approve(address(dvp), type(uint256).max);
        cash.approve(address(dvp), type(uint256).max);
        vm.stopPrank();
        vm.startPrank(buyer);
        feeToken.approve(address(dvp), type(uint256).max);
        cash.approve(address(dvp), type(uint256).max);
        vm.stopPrank();
    }

    function test_lockAsset_feeOnTransferAssetToken_reverts() public {
        vm.prank(seller);
        vm.expectRevert(
            abi.encodeWithSelector(DvpSettlement.UnexpectedTransferAmount.selector, address(feeToken), 1_000, 990)
        );
        dvp.lockAsset(keccak256("t1"), buyer, feeToken, 1_000, cash, 100e6, expiry);
    }

    function test_lockPayment_feeOnTransferPaymentToken_reverts() public {
        vm.prank(buyer);
        vm.expectRevert(
            abi.encodeWithSelector(DvpSettlement.UnexpectedTransferAmount.selector, address(feeToken), 100e6, 99e6)
        );
        dvp.lockPayment(keccak256("t2"), seller, cash, 10, feeToken, 100e6, expiry);
    }

    /// @dev The fee token only matters on the escrowed leg: the counterparty's leg is paid straight
    ///      to the other party at settlement, so a lock with a fee-free escrow still works.
    function test_lockAsset_feeFreeEscrowLeg_stillWorks() public {
        vm.prank(seller);
        bytes32 tradeId = dvp.lockAsset(keccak256("t3"), buyer, cash, 100e6, feeToken, 1_000, expiry);
        assertEq(cash.balanceOf(address(dvp)), 100e6);
        assertEq(dvp.termsHashOf(tradeId) != bytes32(0), true);
    }
}
