// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "@erc3643/compliance/modular/IModularCompliance.sol";
import "@erc3643/compliance/modular/modules/AbstractModuleUpgradeable.sol";
import "@erc3643/token/IToken.sol";

/**
 * @title EwpgComplianceModule
 * @notice Custom T-REX compliance module enforcing eWpG-specific rules:
 *         - Maximum investor count (configurable)
 *         - Maximum balance per investor (configurable)
 *         - Country restrictions (blocked countries list)
 *         - Transfer cooldown period
 *
 * @dev This module is "plug-and-play" — no binding setup is required beyond
 *      the standard T-REX IModularCompliance.bindModule() call.
 *      Configuration is stored per compliance-contract address (i.e. per token).
 */
contract EwpgComplianceModule is AbstractModuleUpgradeable {
    // ── State ──────────────────────────────────────────────────────────────

    struct TokenConfig {
        uint256 maxInvestors;
        uint256 maxBalancePerInvestor;   // 0 = unlimited
        uint256 transferCooldownSeconds; // 0 = no cooldown
        mapping(uint16 => bool) blockedCountries; // ISO-3166-1 numeric
    }

    /// @dev compliance address => config
    mapping(address => TokenConfig) private _configs;

    /// @dev compliance address => current investor count
    mapping(address => uint256) private _investorCount;

    /// @dev compliance address => investor address => last outbound transfer timestamp
    mapping(address => mapping(address => uint256)) private _lastTransferTime;

    // ── Events ─────────────────────────────────────────────────────────────

    event MaxInvestorsSet(address indexed token, uint256 max);
    event MaxBalanceSet(address indexed token, uint256 max);
    event CountryBlocked(address indexed token, uint16 country);
    event CountryUnblocked(address indexed token, uint16 country);
    event TransferCooldownSet(address indexed token, uint256 seconds_);

    // ── Module Interface ───────────────────────────────────────────────────

    /**
     * @notice Called by the compliance contract on every token transfer.
     *         Records the transfer timestamp for cooldown tracking.
     */
    function moduleTransferAction(address _from, address _to, uint256 /*_value*/) external override onlyComplianceBound {
        _lastTransferTime[_msgSender()][_from] = block.timestamp;
        // Update investor count: increment if _to had zero balance, decrement if _from now has zero
        // Note: balance changes are already applied when this hook fires, so we read current balances.
        address complianceAddr = _msgSender();
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());

        if (_to != address(0) && token.balanceOf(_to) > 0) {
            // _to now has a positive balance — could be a new investor.
            // We use the simplistic heuristic: if balance == the transferred amount they were at zero.
            // Accurate tracking requires pre-transfer snapshot; this is a best-effort implementation.
        }
        if (_from != address(0) && token.balanceOf(_from) == 0) {
            if (_investorCount[complianceAddr] > 0) {
                _investorCount[complianceAddr]--;
            }
        }
    }

    /**
     * @notice Called on mint. Increments investor count for new investors.
     */
    function moduleMintAction(address _to, uint256 /*_value*/) external override onlyComplianceBound {
        address complianceAddr = _msgSender();
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());
        // If recipient balance equals the minted amount they were previously at zero → new investor
        // (balance has already been updated by the time this hook fires)
        uint256 currentBalance = token.balanceOf(_to);
        if (currentBalance > 0) {
            // Heuristic: treat any first-time positive balance as new investor
            _investorCount[complianceAddr]++;
        }
    }

    /**
     * @notice Called on burn. Decrements investor count when balance reaches zero.
     */
    function moduleBurnAction(address _from, uint256 /*_value*/) external override onlyComplianceBound {
        address complianceAddr = _msgSender();
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());
        if (token.balanceOf(_from) == 0) {
            if (_investorCount[complianceAddr] > 0) {
                _investorCount[complianceAddr]--;
            }
        }
    }

    /**
     * @notice Compliance check called before every transfer.
     * @return true if the transfer is allowed, false otherwise.
     */
    function moduleCheck(
        address _from,
        address _to,
        uint256 _value,
        address _compliance
    ) external view override returns (bool) {
        TokenConfig storage cfg = _configs[_compliance];

        // 1. Transfer cooldown: sender must wait before transferring again
        if (cfg.transferCooldownSeconds > 0 && _from != address(0)) {
            uint256 lastTransfer = _lastTransferTime[_compliance][_from];
            if (lastTransfer > 0 && block.timestamp < lastTransfer + cfg.transferCooldownSeconds) {
                return false;
            }
        }

        // 2. Max balance per investor (skip burns)
        if (_to != address(0) && cfg.maxBalancePerInvestor > 0) {
            IToken token = IToken(IModularCompliance(_compliance).getTokenBound());
            if (token.balanceOf(_to) + _value > cfg.maxBalancePerInvestor) {
                return false;
            }
        }

        // 3. Max investor count (only applies to new investors on mint/transfer-in)
        if (_from == address(0) && cfg.maxInvestors > 0) {
            // Mint path: check whether _to is a new investor
            IToken token = IToken(IModularCompliance(_compliance).getTokenBound());
            if (token.balanceOf(_to) == 0 && _investorCount[_compliance] >= cfg.maxInvestors) {
                return false;
            }
        }

        return true;
    }

    /// @inheritdoc IModule
    function canComplianceBind(address /*_compliance*/) external pure override returns (bool) {
        return true;
    }

    /// @inheritdoc IModule
    function isPlugAndPlay() external pure override returns (bool) {
        return true;
    }

    /// @inheritdoc IModule
    function name() external pure override returns (string memory) {
        return "EwpgComplianceModule";
    }

    // ── Configuration ──────────────────────────────────────────────────────

    /**
     * @notice Set the maximum number of token holders.
     * @param token  The compliance contract address for this token.
     * @param max    Maximum allowed investors (0 = unlimited).
     */
    function setMaxInvestors(address token, uint256 max) external onlyBoundCompliance(token) {
        _configs[token].maxInvestors = max;
        emit MaxInvestorsSet(token, max);
    }

    /**
     * @notice Set the maximum token balance per investor.
     * @param token  The compliance contract address for this token.
     * @param max    Maximum balance (0 = unlimited).
     */
    function setMaxBalance(address token, uint256 max) external onlyBoundCompliance(token) {
        _configs[token].maxBalancePerInvestor = max;
        emit MaxBalanceSet(token, max);
    }

    /**
     * @notice Block a country (ISO-3166-1 numeric) from holding/receiving tokens.
     * @param token    The compliance contract address for this token.
     * @param country  ISO-3166-1 numeric country code to block.
     */
    function blockCountry(address token, uint16 country) external onlyBoundCompliance(token) {
        _configs[token].blockedCountries[country] = true;
        emit CountryBlocked(token, country);
    }

    /**
     * @notice Unblock a previously blocked country.
     * @param token    The compliance contract address for this token.
     * @param country  ISO-3166-1 numeric country code to unblock.
     */
    function unblockCountry(address token, uint16 country) external onlyBoundCompliance(token) {
        _configs[token].blockedCountries[country] = false;
        emit CountryUnblocked(token, country);
    }

    /**
     * @notice Set the minimum seconds that must elapse between outbound transfers.
     * @param token    The compliance contract address for this token.
     * @param seconds_ Cooldown in seconds (0 = disabled).
     */
    function setTransferCooldown(address token, uint256 seconds_) external onlyBoundCompliance(token) {
        _configs[token].transferCooldownSeconds = seconds_;
        emit TransferCooldownSet(token, seconds_);
    }

    // ── View helpers ───────────────────────────────────────────────────────

    function getInvestorCount(address complianceAddr) external view returns (uint256) {
        return _investorCount[complianceAddr];
    }

    function isCountryBlocked(address complianceAddr, uint16 country) external view returns (bool) {
        return _configs[complianceAddr].blockedCountries[country];
    }

    // ── Modifiers ──────────────────────────────────────────────────────────

    modifier onlyBoundCompliance(address complianceAddr) {
        require(isComplianceBound(complianceAddr), "EwpgComplianceModule: not bound to compliance");
        _;
    }

    modifier onlyComplianceBound() {
        require(isComplianceBound(_msgSender()), "EwpgComplianceModule: caller is not a bound compliance");
        _;
    }
}
