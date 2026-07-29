// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "@erc3643/compliance/modular/IModularCompliance.sol";
import "@erc3643/compliance/modular/modules/AbstractModuleUpgradeable.sol";
import "@erc3643/token/IToken.sol";
import "@erc3643/ERC-3643/IERC3643IdentityRegistry.sol";

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
        // Contract addresses flagged as a licensed nominee/omnibus pool (see
        // {setNomineePool}) are exempt from maxBalancePerInvestor and maxInvestors, since a
        // pool nets many LPs' exposure behind one address and a per-investor cap on that
        // single address would defeat the cap's regulatory intent. Country-block and
        // cooldown checks still apply unconditionally.
        mapping(address => bool) isNomineePool;
    }

    /// @dev compliance address => config
    mapping(address => TokenConfig) private _configs;

    /// @dev compliance address => current investor count
    mapping(address => uint256) private _investorCount;

    /// @dev compliance address => investor address => last outbound transfer timestamp
    mapping(address => mapping(address => uint256)) private _lastTransferTime;

    /// @dev compliance address => holder address => currently holds a positive balance.
    ///      Tracked explicitly so mint/transfer/burn each flip an investor's membership
    ///      exactly once, regardless of which hook observes the balance change.
    mapping(address => mapping(address => bool)) private _isInvestor;

    // ── Events ─────────────────────────────────────────────────────────────

    event MaxInvestorsSet(address indexed token, uint256 max);
    event MaxBalanceSet(address indexed token, uint256 max);
    event CountryBlocked(address indexed token, uint16 country);
    event CountryUnblocked(address indexed token, uint16 country);
    event TransferCooldownSet(address indexed token, uint256 seconds_);
    event NomineePoolSet(address indexed token, address indexed pool, bool isNominee);

    // ── Module Interface ───────────────────────────────────────────────────

    /**
     * @notice Called by the compliance contract on every token transfer.
     *         Records the transfer timestamp for cooldown tracking and re-syncs
     *         investor membership for both legs of the transfer.
     */
    function moduleTransferAction(address _from, address _to, uint256 /*_value*/) external override onlyComplianceCall {
        address complianceAddr = _msgSender();
        _lastTransferTime[complianceAddr][_from] = block.timestamp;

        // Balances are already updated by the time this hook fires, so a plain
        // balanceOf() read reflects the post-transfer state for both sides.
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());
        _syncInvestorStatus(complianceAddr, _to, token.balanceOf(_to));
        _syncInvestorStatus(complianceAddr, _from, token.balanceOf(_from));
    }

    /**
     * @notice Called on mint. Increments investor count for new investors.
     */
    function moduleMintAction(address _to, uint256 /*_value*/) external override onlyComplianceCall {
        address complianceAddr = _msgSender();
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());
        _syncInvestorStatus(complianceAddr, _to, token.balanceOf(_to));
    }

    /**
     * @notice Called on burn. Decrements investor count when balance reaches zero.
     */
    function moduleBurnAction(address _from, uint256 /*_value*/) external override onlyComplianceCall {
        address complianceAddr = _msgSender();
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());
        _syncInvestorStatus(complianceAddr, _from, token.balanceOf(_from));
    }

    /**
     * @dev Reconciles the tracked investor-membership flag for `holder` against its
     *      current on-chain balance and adjusts `_investorCount` by at most one.
     *      Idempotent: calling it repeatedly with the same balance is a no-op, so it
     *      is safe to call from mint, burn, and both legs of a transfer.
     */
    function _syncInvestorStatus(address complianceAddr, address holder, uint256 balance) private {
        if (holder == address(0)) {
            return;
        }
        bool wasInvestor = _isInvestor[complianceAddr][holder];
        bool isInvestor = balance > 0;
        if (isInvestor == wasInvestor) {
            return;
        }
        _isInvestor[complianceAddr][holder] = isInvestor;
        if (isInvestor) {
            _investorCount[complianceAddr]++;
        } else if (_investorCount[complianceAddr] > 0) {
            _investorCount[complianceAddr]--;
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

        if (_to == address(0)) {
            return true;
        }

        IToken token = IToken(IModularCompliance(_compliance).getTokenBound());

        // 2. Country restriction: the recipient's registered country must not be blocked.
        //    Skip on mint/transfer while the identity registry has no country on file yet
        //    (registerIdentity always sets a country before isVerified() can pass, so this
        //    only guards against an unset default of 0).
        IERC3643IdentityRegistry ir = token.identityRegistry();
        uint16 recipientCountry = ir.investorCountry(_to);
        if (cfg.blockedCountries[recipientCountry]) {
            return false;
        }

        // A nominee/omnibus pool (a contract address explicitly flagged by the token's
        // registry, holding a valid NOMINEE claim off-chain) nets many LPs' exposure behind
        // one address — max-balance and max-investor-count exist to cap *individual* investor
        // exposure/count, so they don't apply to it. The flag only ever takes effect for a
        // contract address; flagging an EOA is a no-op (see {setNomineePool}).
        if (cfg.isNomineePool[_to] && _to.code.length > 0) {
            return true;
        }

        uint256 recipientBalance = token.balanceOf(_to);

        // 3. Max balance per investor
        if (cfg.maxBalancePerInvestor > 0 && recipientBalance + _value > cfg.maxBalancePerInvestor) {
            return false;
        }

        // 4. Max investor count: applies whenever the recipient is a *new* investor,
        //    whether they receive their first tokens via mint or via transfer-in.
        if (cfg.maxInvestors > 0 && recipientBalance == 0 && _investorCount[_compliance] >= cfg.maxInvestors) {
            return false;
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

    /**
     * @notice Flag (or unflag) a contract address as a licensed nominee/omnibus pool for this
     *         token, exempting it from maxBalancePerInvestor and maxInvestors in
     *         {moduleCheck}. Country-block and cooldown checks are unaffected. Setting this
     *         is an explicit, auditable registry action per token — a pool never self-declares
     *         nominee status. Flagging an EOA has no effect; only a contract address can be
     *         treated as a pool (see {moduleCheck}).
     * @param token     The compliance contract address for this token.
     * @param pool      The nominee/omnibus pool contract address.
     * @param isNominee True to flag the pool as a nominee, false to unflag it.
     */
    function setNomineePool(address token, address pool, bool isNominee) external onlyBoundCompliance(token) {
        _configs[token].isNomineePool[pool] = isNominee;
        emit NomineePoolSet(token, pool, isNominee);
    }

    // ── View helpers ───────────────────────────────────────────────────────

    function getInvestorCount(address complianceAddr) external view returns (uint256) {
        return _investorCount[complianceAddr];
    }

    function isCountryBlocked(address complianceAddr, uint16 country) external view returns (bool) {
        return _configs[complianceAddr].blockedCountries[country];
    }

    function isNomineePool(address complianceAddr, address pool) external view returns (bool) {
        return _configs[complianceAddr].isNomineePool[pool];
    }

}
