// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "../interfaces/IEwpgCompliant.sol";
import "../interfaces/IEwpgAdminControls.sol";

/// @title EwpgCompliance
/// @notice Abstract base contract that enforces a registry-controlled whitelist, address-level
///         freeze, and global pause for all eWpG token contracts.
///
/// @dev Implements both IEwpgCompliant (whitelist) and IEwpgAdminControls (regulatory powers).
///      Concrete token contracts inherit this and call the internal helpers from their
///      `_update` hook to enforce compliance on every transfer.
///
///      Regulatory powers summary:
///      - pause / unpause  : MiCAR Art. 36, 84; eWpG §24
///      - freezeAddress    : AWG §17, GwG §40; MiCAR Art. 36
///      - forcedTransfer / forceBurn are implemented per token contract
///        (argument types differ between ERC-20, ERC-721, ERC-1155).
abstract contract EwpgCompliance is IEwpgCompliant, IEwpgAdminControls {
    /// @notice The backend registry wallet — sole authority over all admin operations.
    address public registry;

    /// @notice Wallet nominated to take over the registry authority (two-step handover).
    address public pendingRegistry;

    mapping(address => bool) private _whitelisted;
    mapping(address => bool) private _frozenAccounts;
    bool private _paused;
    uint256 private _supplyCap; // 0 = unlimited

    // ── Events (registry handover) ─────────────────────────────────────────────

    event RegistryTransferStarted(address indexed currentRegistry, address indexed pendingRegistry);
    event RegistryTransferred(address indexed previousRegistry, address indexed newRegistry);

    // ── Modifiers ─────────────────────────────────────────────────────────────

    /// @notice Restricts access to the registry wallet.
    modifier onlyRegistry() {
        require(msg.sender == registry, "EwpgCompliance: caller is not registry");
        _;
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    constructor(address _registry) {
        require(_registry != address(0), "EwpgCompliance: zero registry address");
        registry = _registry;
    }

    // ── Registry handover (key rotation / operator succession) ────────────────

    /// @notice Begins handover of the registry authority to a new wallet.
    /// @dev Two-step (propose + accept) so a typo'd address cannot brick the
    ///      contract: without a handover path, rotating or losing the registry
    ///      key would leave the token permanently without pause/freeze/forced
    ///      transfer capability — incompatible with the registry operator's
    ///      duty of perpetual administrability (eWpG §7, KryptoWTransparenzV).
    /// @param newRegistry The wallet nominated to take over.
    function transferRegistry(address newRegistry) external onlyRegistry {
        require(newRegistry != address(0), "EwpgCompliance: zero registry address");
        pendingRegistry = newRegistry;
        emit RegistryTransferStarted(registry, newRegistry);
    }

    /// @notice Cancels a pending handover.
    function cancelRegistryTransfer() external onlyRegistry {
        pendingRegistry = address(0);
        emit RegistryTransferStarted(registry, address(0));
    }

    /// @notice Completes the handover. Only the nominated wallet can accept,
    ///         proving it controls its key before authority moves.
    function acceptRegistry() external {
        require(msg.sender == pendingRegistry, "EwpgCompliance: caller is not pending registry");
        emit RegistryTransferred(registry, pendingRegistry);
        registry = pendingRegistry;
        pendingRegistry = address(0);
    }

    // ── Whitelist (IEwpgCompliant) ─────────────────────────────────────────────

    /// @inheritdoc IEwpgCompliant
    function whitelist(address account) external override onlyRegistry {
        require(account != address(0), "EwpgCompliance: zero address");
        _whitelisted[account] = true;
        emit Whitelisted(account);
    }

    /// @inheritdoc IEwpgCompliant
    function removeFromWhitelist(address account) external override onlyRegistry {
        _whitelisted[account] = false;
        emit RemovedFromWhitelist(account);
    }

    /// @inheritdoc IEwpgCompliant
    function isWhitelisted(address account) public view override returns (bool) {
        return _whitelisted[account];
    }

    // ── Pause / Unpause (MiCAR Art. 36, 84; eWpG §24) ────────────────────────

    /// @inheritdoc IEwpgAdminControls
    function pause() external override onlyRegistry {
        require(!_paused, "EwpgCompliance: already paused");
        _paused = true;
        emit Paused(msg.sender);
    }

    /// @inheritdoc IEwpgAdminControls
    function unpause() external override onlyRegistry {
        require(_paused, "EwpgCompliance: not paused");
        _paused = false;
        emit Unpaused(msg.sender);
    }

    /// @inheritdoc IEwpgAdminControls
    function isPaused() public view override returns (bool) {
        return _paused;
    }

    // ── Address freeze (AWG §17, GwG §40; MiCAR Art. 36) ─────────────────────

    /// @inheritdoc IEwpgAdminControls
    function freezeAddress(address account, string calldata reason) external override onlyRegistry {
        require(account != address(0), "EwpgCompliance: zero address");
        _frozenAccounts[account] = true;
        emit AddressFrozen(account, reason);
    }

    /// @inheritdoc IEwpgAdminControls
    function unfreezeAddress(address account) external override onlyRegistry {
        _frozenAccounts[account] = false;
        emit AddressUnfrozen(account);
    }

    /// @inheritdoc IEwpgAdminControls
    function isFrozen(address account) public view override returns (bool) {
        return _frozenAccounts[account];
    }

    // ── Supply cap (MiCAR Art. 46) ────────────────────────────────────────────

    /// @inheritdoc IEwpgAdminControls
    function setSupplyCap(uint256 newCap) external override onlyRegistry {
        emit SupplyCapUpdated(_supplyCap, newCap);
        _supplyCap = newCap;
    }

    /// @inheritdoc IEwpgAdminControls
    function supplyCap() public view override returns (uint256) {
        return _supplyCap;
    }

    // ── Internal helpers called by token _update hooks ────────────────────────

    /// @notice Reverts if transfers are globally paused or either participant is frozen.
    ///         Call from the token's `_update` hook for all normal (non-forced) transfers.
    /// @param from Source address (address(0) for mints).
    /// @param to   Destination address (address(0) for burns).
    function _requireTransferable(address from, address to) internal view {
        require(!_paused, "EwpgCompliance: transfers are paused");
        if (from != address(0)) {
            require(!_frozenAccounts[from], "EwpgCompliance: sender is frozen");
        }
        if (to != address(0)) {
            require(!_frozenAccounts[to], "EwpgCompliance: recipient is frozen");
        }
    }

    /// @notice Reverts if minting `addAmount` tokens would breach the supply cap.
    ///         Call from `mint` / `mintBatch` before `_mint`.
    /// @param currentSupply The token's current `totalSupply()`.
    /// @param addAmount     The amount being minted.
    function _requireWithinCap(uint256 currentSupply, uint256 addAmount) internal view {
        if (_supplyCap > 0) {
            require(currentSupply + addAmount <= _supplyCap, "EwpgCompliance: supply cap exceeded");
        }
    }
}
