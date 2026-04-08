// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

/// @title IEwpgAdminControls
/// @notice Standardised regulatory-control interface implemented by all eWpG token contracts.
///
/// @dev Legal basis:
///   - pause / unpause  : MiCAR Art. 36, 84; eWpG §24
///   - freezeAddress    : AWG §17, GwG §40; MiCAR Art. 36 (sanctions/AML)
///   - forcedTransfer   : eWpG §24 Berichtigung (BaFin register-correction order)
///   - forceBurn        : eWpG §26 Einziehung (compulsory cancellation / confiscation)
///   - setSupplyCap     : MiCAR Art. 46 (regulatory issuance limit)
interface IEwpgAdminControls {
    // ── Events ────────────────────────────────────────────────────────────────

    /// @notice Emitted when all transfers are suspended.
    event Paused(address indexed registry);

    /// @notice Emitted when normal transfer processing is resumed.
    event Unpaused(address indexed registry);

    /// @notice Emitted when an address is frozen (blocked from sending or receiving).
    /// @param account The frozen wallet.
    /// @param reason  Short human-readable reason (for on-chain audit trail).
    event AddressFrozen(address indexed account, string reason);

    /// @notice Emitted when a frozen address is unfrozen.
    event AddressUnfrozen(address indexed account);

    /// @notice Emitted after a registry-initiated forced transfer (eWpG §24).
    event ForcedTransfer(address indexed from, address indexed to, uint256 value, string legalBasis);

    /// @notice Emitted after a registry-initiated forced burn / Einziehung (eWpG §26).
    event ForceBurned(address indexed from, uint256 value, string legalBasis);

    /// @notice Emitted when the supply cap is changed.
    event SupplyCapUpdated(uint256 oldCap, uint256 newCap);

    // ── Pause / unpause ───────────────────────────────────────────────────────

    /// @notice Suspends all token transfers. Only the registry wallet may call this.
    ///         Legal basis: MiCAR Art. 36 / 84; eWpG §24.
    function pause() external;

    /// @notice Resumes token transfers. Only the registry wallet may call this.
    function unpause() external;

    /// @notice Returns true while all transfers are suspended.
    function isPaused() external view returns (bool);

    // ── Address freeze ────────────────────────────────────────────────────────

    /// @notice Prevents a specific address from sending or receiving tokens.
    ///         Legal basis: AWG §17 (sanctions list); GwG §40; MiCAR Art. 36.
    /// @param account Address to freeze.
    /// @param reason  Short human-readable reason logged in the FrozenAddress event.
    function freezeAddress(address account, string calldata reason) external;

    /// @notice Lifts a previous freeze on an address.
    function unfreezeAddress(address account) external;

    /// @notice Returns true if the address is currently frozen.
    function isFrozen(address account) external view returns (bool);

    // ── Forced transfer ───────────────────────────────────────────────────────

    /// @notice Transfers tokens from `from` to `to` regardless of whitelist or freeze status.
    ///         Legal basis: eWpG §24 Berichtigung (BaFin or court order to correct registry).
    /// @param from       Source address.
    /// @param to         Destination address.
    /// @param value      Amount (ERC-20/ERC-1155) or tokenId (ERC-721).
    /// @param legalBasis Reference to the legal authority (e.g. "BaFin order 2025-01").
    function forcedTransfer(address from, address to, uint256 value, string calldata legalBasis) external;

    // ── Forced burn (Einziehung) ──────────────────────────────────────────────

    /// @notice Burns tokens from `from` regardless of whitelist or freeze status.
    ///         Legal basis: eWpG §26 Einziehung (compulsory cancellation by BaFin).
    /// @param from       Address whose tokens are cancelled.
    /// @param value      Amount (ERC-20/ERC-1155) or tokenId (ERC-721).
    /// @param legalBasis Reference to the legal authority (e.g. "BaFin Einziehungsverfügung").
    function forceBurn(address from, uint256 value, string calldata legalBasis) external;

    // ── Supply cap ────────────────────────────────────────────────────────────

    /// @notice Updates the maximum total supply. 0 = unlimited.
    ///         Legal basis: MiCAR Art. 46 (regulatory cap on crypto-asset issuance).
    /// @param newCap New supply cap in smallest token unit; 0 removes the cap.
    function setSupplyCap(uint256 newCap) external;

    /// @notice Current supply cap. 0 = no cap enforced.
    function supplyCap() external view returns (uint256);
}
