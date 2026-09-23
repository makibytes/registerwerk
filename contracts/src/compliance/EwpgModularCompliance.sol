// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "@erc3643/compliance/modular/IModularCompliance.sol";
import "@erc3643/compliance/modular/modules/AbstractModuleUpgradeable.sol";
import "@erc3643/token/IToken.sol";
import "@erc3643/ERC-3643/IERC3643IdentityRegistry.sol";
import "@erc3643/roles/IERC173.sol";

/**
 * @title EwpgComplianceModule
 * @notice Custom T-REX compliance module enforcing eWpG-specific rules:
 *         - Maximum investor count (configurable), counted per ONCHAINID
 *         - Maximum balance per investor (configurable), aggregated per ONCHAINID
 *         - Country restrictions (blocked countries list; fails closed on country 0)
 *         - Transfer cooldown period
 *
 * @dev This module is "plug-and-play" — no binding setup is required beyond
 *      the standard T-REX IModularCompliance.bindModule() call.
 *      Configuration is stored per compliance-contract address (i.e. per token) and may
 *      only be changed by that compliance's owner or by the compliance itself (i.e. via
 *      the owner-only {IModularCompliance-callModuleFunction}).
 *
 *      "Investor" means an ONCHAINID, as in upstream T-REX: a legal entity may bind several
 *      wallets to one identity, and those wallets share one balance cap and count as one
 *      investor. Balances are tracked by delta-sync ({_syncWallet}): each wallet's last
 *      seen balance and identity are remembered, so a re-bound wallet (`updateIdentity`,
 *      `recoveryAddress`) moves its contribution to the new identity on its next touch, and
 *      holders that existed before the module was bound are picked up via {syncHolders}.
 */
contract EwpgComplianceModule is AbstractModuleUpgradeable {
    // ── Errors ─────────────────────────────────────────────────────────────

    error CallerNotComplianceAdmin(address caller, address compliance);

    // ── State ──────────────────────────────────────────────────────────────

    struct TokenConfig {
        uint256 maxInvestors;
        uint256 maxBalancePerInvestor;   // 0 = unlimited
        uint256 transferCooldownSeconds; // 0 = no cooldown
        mapping(uint16 => bool) blockedCountries; // ISO-3166-1 numeric
        // Number of countries currently in `blockedCountries`. While > 0, a recipient with
        // no country on file (0) is rejected: a geographic restriction cannot be verified
        // against an unknown country, so it fails closed.
        uint256 blockedCountryCount;
        // Contract addresses flagged as a licensed nominee/omnibus pool (see
        // {setNomineePool}) are exempt from maxBalancePerInvestor and maxInvestors, since a
        // pool nets many LPs' exposure behind one address and a per-investor cap on that
        // single address would defeat the cap's regulatory intent. Country-block and
        // cooldown checks still apply unconditionally.
        mapping(address => bool) isNomineePool;
    }

    /// @dev compliance address => config
    mapping(address => TokenConfig) private _configs;

    /// @dev compliance address => current investor (identity) count
    mapping(address => uint256) private _investorCount;

    /// @dev compliance address => investor address => last outbound transfer timestamp
    mapping(address => mapping(address => uint256)) private _lastTransferTime;

    /// @dev compliance address => wallet => balance last credited to that wallet's identity
    mapping(address => mapping(address => uint256)) private _walletSeenBalance;

    /// @dev compliance address => wallet => identity its seen balance is credited to
    mapping(address => mapping(address => address)) private _walletIdentity;

    /// @dev compliance address => identity => sum of its wallets' seen balances
    mapping(address => mapping(address => uint256)) private _identityBalance;

    // ── Events ─────────────────────────────────────────────────────────────

    event MaxInvestorsSet(address indexed token, uint256 max);
    event MaxBalanceSet(address indexed token, uint256 max);
    event CountryBlocked(address indexed token, uint16 country);
    event CountryUnblocked(address indexed token, uint16 country);
    event TransferCooldownSet(address indexed token, uint256 seconds_);
    event NomineePoolSet(address indexed token, address indexed pool, bool isNominee);

    // ── Access control ─────────────────────────────────────────────────────

    /**
     * @dev Config is per compliance, so only that compliance's owner (the token issuer's
     *      registry wallet) or the compliance itself — reached through its owner-only
     *      `callModuleFunction`, which is also how `TREXFactory` applies
     *      `complianceSettings` at deploy time — may change it. The upstream
     *      `onlyBoundCompliance` only checks that the compliance is bound, not who calls.
     *      Note T-REX compliance ownership is 2-step: the owner named at deploy time must
     *      `acceptOwnership()` on the compliance before it can configure this module.
     */
    modifier onlyComplianceAdmin(address _compliance) {
        if (msg.sender != _compliance && msg.sender != IERC173(_compliance).owner()) {
            revert CallerNotComplianceAdmin(msg.sender, _compliance);
        }
        _;
    }

    // ── Module Interface ───────────────────────────────────────────────────

    /**
     * @notice Called by the compliance contract on every token transfer.
     *         Records the transfer timestamp for cooldown tracking and re-syncs
     *         identity balances for both legs of the transfer.
     */
    function moduleTransferAction(address _from, address _to, uint256 /*_value*/) external override onlyComplianceCall {
        address complianceAddr = _msgSender();
        _lastTransferTime[complianceAddr][_from] = block.timestamp;

        // Balances are already updated by the time this hook fires, so a plain
        // balanceOf() read reflects the post-transfer state for both sides.
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());
        _syncWallet(complianceAddr, token, _to);
        _syncWallet(complianceAddr, token, _from);
    }

    /**
     * @notice Called on mint. Increments investor count for new investors.
     */
    function moduleMintAction(address _to, uint256 /*_value*/) external override onlyComplianceCall {
        address complianceAddr = _msgSender();
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());
        _syncWallet(complianceAddr, token, _to);
    }

    /**
     * @notice Called on burn. Decrements investor count when an identity's balance reaches zero.
     */
    function moduleBurnAction(address _from, uint256 /*_value*/) external override onlyComplianceCall {
        address complianceAddr = _msgSender();
        IToken token = IToken(IModularCompliance(complianceAddr).getTokenBound());
        _syncWallet(complianceAddr, token, _from);
    }

    /**
     * @dev Returns the ONCHAINID `wallet` is registered under, or the wallet itself when it
     *      has none (so unregistered holders, e.g. a nominee pool without an identity, still
     *      count as a distinct investor).
     */
    function _identityOf(IToken token, address wallet) private view returns (address id) {
        id = address(token.identityRegistry().identity(wallet));
        if (id == address(0)) {
            id = wallet;
        }
    }

    /**
     * @dev Reconciles `wallet`'s contribution to its identity's balance with its current
     *      on-chain balance and identity binding: removes the previously credited balance from
     *      the previously credited identity, credits the current balance to the current
     *      identity, and flips `_investorCount` on the identities' zero crossings.
     *      Idempotent: a second call with unchanged balance and identity is a no-op, so it is
     *      safe to call from mint, burn, both legs of a transfer and {syncHolders}.
     */
    function _syncWallet(address complianceAddr, IToken token, address wallet) private {
        if (wallet == address(0)) {
            return;
        }
        uint256 balance = token.balanceOf(wallet);
        address id = _identityOf(token, wallet);
        uint256 seen = _walletSeenBalance[complianceAddr][wallet];
        address seenId = _walletIdentity[complianceAddr][wallet];
        if (balance == seen && id == seenId) {
            return;
        }
        if (seen > 0) {
            uint256 remaining = _identityBalance[complianceAddr][seenId] - seen;
            _identityBalance[complianceAddr][seenId] = remaining;
            if (remaining == 0 && _investorCount[complianceAddr] > 0) {
                _investorCount[complianceAddr]--;
            }
        }
        if (balance > 0) {
            if (_identityBalance[complianceAddr][id] == 0) {
                _investorCount[complianceAddr]++;
            }
            _identityBalance[complianceAddr][id] += balance;
        }
        _walletSeenBalance[complianceAddr][wallet] = balance;
        _walletIdentity[complianceAddr][wallet] = id;
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
        //    While any country is blocked, a recipient without a country on file (0) fails
        //    closed — T-REX does not force a non-zero country at registerIdentity, and an
        //    unknown country cannot be shown not to be a blocked one.
        if (cfg.blockedCountryCount > 0) {
            uint16 recipientCountry = token.identityRegistry().investorCountry(_to);
            if (recipientCountry == 0 || cfg.blockedCountries[recipientCountry]) {
                return false;
            }
        }

        // A nominee/omnibus pool (a contract address explicitly flagged by the token's
        // registry, holding a valid NOMINEE claim off-chain) nets many LPs' exposure behind
        // one address — max-balance and max-investor-count exist to cap *individual* investor
        // exposure/count, so they don't apply to it. The flag only ever takes effect for a
        // contract address; flagging an EOA is a no-op (see {setNomineePool}).
        if (cfg.isNomineePool[_to] && _to.code.length > 0) {
            return true;
        }

        address toId = _identityOf(token, _to);
        // Moving units between two wallets of the same identity changes neither the
        // identity's balance nor the investor count.
        if (_from != address(0) && _identityOf(token, _from) == toId) {
            return true;
        }

        // Tracked identity balance, with the recipient wallet's own contribution taken live
        // (it may not have been synced since a re-binding or recovery).
        uint256 identityBalance = _identityBalance[_compliance][toId] + token.balanceOf(_to);
        if (_walletIdentity[_compliance][_to] == toId) {
            identityBalance -= _walletSeenBalance[_compliance][_to];
        }

        // 3. Max balance per investor (identity)
        if (cfg.maxBalancePerInvestor > 0 && identityBalance + _value > cfg.maxBalancePerInvestor) {
            return false;
        }

        // 4. Max investor count: applies whenever the recipient identity is a *new* investor,
        //    whether it receives its first tokens via mint or via transfer-in.
        if (cfg.maxInvestors > 0 && identityBalance == 0 && _investorCount[_compliance] >= cfg.maxInvestors) {
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
     * @notice Set the maximum number of token holders (identities).
     * @param token  The compliance contract address for this token.
     * @param max    Maximum allowed investors (0 = unlimited).
     */
    function setMaxInvestors(address token, uint256 max)
        external onlyBoundCompliance(token) onlyComplianceAdmin(token)
    {
        _configs[token].maxInvestors = max;
        emit MaxInvestorsSet(token, max);
    }

    /**
     * @notice Set the maximum token balance per investor (identity).
     * @param token  The compliance contract address for this token.
     * @param max    Maximum balance (0 = unlimited).
     */
    function setMaxBalance(address token, uint256 max)
        external onlyBoundCompliance(token) onlyComplianceAdmin(token)
    {
        _configs[token].maxBalancePerInvestor = max;
        emit MaxBalanceSet(token, max);
    }

    /**
     * @notice Block a country (ISO-3166-1 numeric) from holding/receiving tokens.
     * @param token    The compliance contract address for this token.
     * @param country  ISO-3166-1 numeric country code to block.
     */
    function blockCountry(address token, uint16 country)
        external onlyBoundCompliance(token) onlyComplianceAdmin(token)
    {
        TokenConfig storage cfg = _configs[token];
        if (!cfg.blockedCountries[country]) {
            cfg.blockedCountries[country] = true;
            cfg.blockedCountryCount++;
        }
        emit CountryBlocked(token, country);
    }

    /**
     * @notice Unblock a previously blocked country.
     * @param token    The compliance contract address for this token.
     * @param country  ISO-3166-1 numeric country code to unblock.
     */
    function unblockCountry(address token, uint16 country)
        external onlyBoundCompliance(token) onlyComplianceAdmin(token)
    {
        TokenConfig storage cfg = _configs[token];
        if (cfg.blockedCountries[country]) {
            cfg.blockedCountries[country] = false;
            cfg.blockedCountryCount--;
        }
        emit CountryUnblocked(token, country);
    }

    /**
     * @notice Set the minimum seconds that must elapse between outbound transfers.
     * @param token    The compliance contract address for this token.
     * @param seconds_ Cooldown in seconds (0 = disabled).
     */
    function setTransferCooldown(address token, uint256 seconds_)
        external onlyBoundCompliance(token) onlyComplianceAdmin(token)
    {
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
    function setNomineePool(address token, address pool, bool isNominee)
        external onlyBoundCompliance(token) onlyComplianceAdmin(token)
    {
        _configs[token].isNomineePool[pool] = isNominee;
        emit NomineePoolSet(token, pool, isNominee);
    }

    /**
     * @notice Re-syncs the identity balances of `wallets` with their current on-chain
     *         balances and identity bindings. Needed once when this module is bound to a
     *         token that already has holders (the hooks only see balances that change after
     *         binding), and after identity re-bindings that no transfer has touched since.
     *         Idempotent: already-synced wallets are no-ops, so it can be re-run with the
     *         full holder list and in batches.
     * @param token    The compliance contract address for this token.
     * @param wallets  Holder wallets to sync.
     */
    function syncHolders(address token, address[] calldata wallets)
        external onlyBoundCompliance(token) onlyComplianceAdmin(token)
    {
        IToken boundToken = IToken(IModularCompliance(token).getTokenBound());
        for (uint256 i = 0; i < wallets.length; i++) {
            _syncWallet(token, boundToken, wallets[i]);
        }
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

    /// @notice Tracked balance of `identity` (an ONCHAINID, or a bare wallet without one).
    function getIdentityBalance(address complianceAddr, address identity) external view returns (uint256) {
        return _identityBalance[complianceAddr][identity];
    }

    /**
     * @notice Scalar config of a compliance, for off-chain read-back / drift checks against
     *         the registry DB. Individual blocked countries are read via {isCountryBlocked}.
     */
    function getConfig(address complianceAddr)
        external
        view
        returns (
            uint256 maxInvestors,
            uint256 maxBalancePerInvestor,
            uint256 transferCooldownSeconds,
            uint256 blockedCountryCount,
            uint256 investorCount
        )
    {
        TokenConfig storage cfg = _configs[complianceAddr];
        return (
            cfg.maxInvestors,
            cfg.maxBalancePerInvestor,
            cfg.transferCooldownSeconds,
            cfg.blockedCountryCount,
            _investorCount[complianceAddr]
        );
    }

}
