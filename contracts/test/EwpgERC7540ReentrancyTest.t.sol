// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuardTransient.sol";
import "../src/tokens/EwpgERC7540.sol";

interface ISendHook {
    function tokensToSend(uint256 amount) external;
}

/// @dev ERC-777 style: tells a contract sender about the transfer before the balances move.
contract SendHookToken is ERC20 {
    constructor() ERC20("Hook Token", "HOOK") {}

    function decimals() public pure override returns (uint8) {
        return 6;
    }

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }

    function _update(address from, address to, uint256 value) internal override {
        if (from != address(0) && from.code.length > 0) ISendHook(from).tokensToSend(value);
        super._update(from, to, value);
    }
}

/// @dev A depositor that re-enters {EwpgERC7540-requestDeposit} from the token's send hook.
contract ReentrantDepositor is ISendHook {
    EwpgERC7540 immutable vault;
    SendHookToken immutable token;
    address immutable owner_;
    bool reentered;

    constructor(EwpgERC7540 vault_, SendHookToken token_, address owner__) {
        vault = vault_;
        token = token_;
        owner_ = owner__;
        token_.approve(address(vault_), type(uint256).max);
    }

    function attack(uint256 assets) external {
        vault.requestDeposit(assets, address(this), owner_);
    }

    function tokensToSend(uint256 amount) external override {
        if (reentered) return;
        reentered = true;
        vault.requestDeposit(amount, address(this), owner_);
    }
}

/// @notice {EwpgERC7540-requestDeposit} books the balance it received. A hook-bearing underlying could
///         re-enter it mid-transfer, so that the inner deposit is counted a second time in the outer
///         one's balance delta — booking cash that never arrived.
contract EwpgERC7540ReentrancyTest is Test {
    EwpgERC7540 vault;
    SendHookToken token;
    ReentrantDepositor attacker;

    address registry = makeAddr("registry");
    address owner = makeAddr("owner");

    function setUp() public {
        token = new SendHookToken();
        vm.startPrank(registry);
        vault = new EwpgERC7540(token, "Async Fund", "RWAF", registry, keccak256("hook-vault"));
        vault.whitelist(owner);
        vm.stopPrank();
        attacker = new ReentrantDepositor(vault, token, owner);
        token.mint(address(attacker), 1_000e6);
    }

    function test_requestDeposit_cannotBeReenteredFromTheTokenHook() public {
        vm.expectRevert(ReentrancyGuardTransient.ReentrancyGuardReentrantCall.selector);
        attacker.attack(100e6);

        // Nothing was booked, and nothing moved.
        assertEq(vault.pendingDepositAssets(), 0);
        assertEq(token.balanceOf(address(vault)), 0);
        assertEq(token.balanceOf(address(attacker)), 1_000e6);
    }
}
