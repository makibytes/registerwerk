// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.36;

// Zama fhEVM imports (available after `forge install zama-ai/fhevm-solidity`).
import "@fhevm/lib/TFHE.sol";
import "@fhevm/gateway/GatewayCaller.sol";
import "@openzeppelin/contracts/access/Ownable.sol";

import "./ConfidentialERC20.sol";

/**
 * @title ConfidentialERC3643
 * @notice Regulated security token that combines ERC-3643 (T-REX) compliance
 *         with ERC-7984 confidential balances backed by Zama's fhEVM.
 *
 * Architecture:
 *  - Extends {ConfidentialERC20} for the encrypted fungible-token layer.
 *  - Delegates KYC/AML enforcement to the T-REX {IIdentityRegistry}; identity
 *    is intentionally public even though amounts are encrypted.
 *  - Delegates transfer-rule enforcement to a pluggable {IModularCompliance}
 *    contract which operates on ciphertexts where possible.
 *  - Owner/agent roles mirror the non-confidential {EwpgERC3643} so the
 *    Registerwerk lifecycle services can treat both variants uniformly.
 *
 * FHEVM/Gateway infrastructure is injected via {ConfidentialERC20.FhevmInfra}
 * at construction — see that contract's class-level note for why these
 * addresses are not hardcoded per network.
 */
interface IIdentityRegistryMinimal {
    function isVerified(address account) external view returns (bool);
}

interface IConfidentialCompliance {
    function canTransfer(address from, address to) external view returns (bool);
    function transferred(address from, address to, euint64 amount) external;
    function created(address to, euint64 amount) external;
    function destroyed(address from, euint64 amount) external;
}

contract ConfidentialERC3643 is ConfidentialERC20 {
    // ── Compliance wiring ──────────────────────────────────────────────────
    address public identityRegistry;
    address public compliance;
    bool    public paused;

    mapping(address => bool) public isAgent;
    mapping(address => bool) public isFrozen;

    event IdentityRegistryUpdated(address indexed registry);
    event ComplianceUpdated(address indexed compliance);
    event Paused();
    event Unpaused();
    event AgentAdded(address indexed agent);
    event AgentRemoved(address indexed agent);
    event AddressFrozen(address indexed who, bool frozen);

    error NotAgent();
    error TransferPaused();
    error RecipientNotVerified();
    error SenderNotVerified();
    error AddressIsFrozen();
    error ComplianceRejected();

    modifier onlyAgent() {
        if (!isAgent[msg.sender] && msg.sender != owner()) revert NotAgent();
        _;
    }

    constructor(
        bytes32 _assetId,
        string memory _name,
        string memory _symbol,
        ConfidentialERC20.FhevmInfra memory _infra,
        address[] memory _initialViewers,
        address _identityRegistry,
        address _compliance,
        address _owner
    ) ConfidentialERC20(_assetId, _name, _symbol, _infra, _initialViewers, _owner) {
        identityRegistry = _identityRegistry;
        compliance       = _compliance;
        isAgent[_owner]  = true;
    }

    // ── Admin ──────────────────────────────────────────────────────────────

    function setIdentityRegistry(address r) external onlyOwner {
        identityRegistry = r;
        emit IdentityRegistryUpdated(r);
    }

    function setCompliance(address c) external onlyOwner {
        compliance = c;
        emit ComplianceUpdated(c);
    }

    function pause()   external onlyAgent { paused = true;  emit Paused(); }
    function unpause() external onlyAgent { paused = false; emit Unpaused(); }

    function addAgent(address a) external onlyOwner {
        isAgent[a] = true;
        emit AgentAdded(a);
    }

    function removeAgent(address a) external onlyOwner {
        isAgent[a] = false;
        emit AgentRemoved(a);
    }

    function setAddressFrozen(address a, bool frozen) external onlyAgent {
        isFrozen[a] = frozen;
        emit AddressFrozen(a, frozen);
    }

    // ── Compliance-gated confidential transfer ─────────────────────────────

    /**
     * @notice Confidential transfer that enforces T-REX identity verification,
     *         freeze status, pause and modular-compliance rules before
     *         performing the encrypted state update.
     */
    function confidentialTransfer(
        address to,
        einput encryptedAmount,
        bytes calldata inputProof
    ) public virtual override returns (euint64 transferred) {
        _checkTransfer(msg.sender, to);
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        transferred = _transfer(msg.sender, to, amount);
        if (compliance != address(0)) {
            IConfidentialCompliance(compliance).transferred(msg.sender, to, transferred);
        }
    }

    function confidentialMint(
        address to,
        einput encryptedAmount,
        bytes calldata inputProof
    ) public virtual override onlyAgent {
        if (identityRegistry != address(0)
            && !IIdentityRegistryMinimal(identityRegistry).isVerified(to)) {
            revert RecipientNotVerified();
        }
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        _balances[to] = TFHE.add(_balances[to], amount);
        _totalSupply  = TFHE.add(_totalSupply, amount);
        _grantBalanceAcl(to);
        _grantSupplyAcl();
        _grantHandleAcl(amount);
        emit ConfidentialMint(to, amount);
        if (compliance != address(0)) {
            IConfidentialCompliance(compliance).created(to, amount);
        }
    }

    function confidentialBurn(
        address from,
        einput encryptedAmount,
        bytes calldata inputProof
    ) public virtual override onlyAgent {
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        ebool enough   = TFHE.le(amount, _balances[from]);
        euint64 actual = TFHE.select(enough, amount, TFHE.asEuint64(0));
        _balances[from] = TFHE.sub(_balances[from], actual);
        _totalSupply    = TFHE.sub(_totalSupply, actual);
        _grantBalanceAcl(from);
        _grantSupplyAcl();
        _grantHandleAcl(actual);
        emit ConfidentialBurn(from, actual);
        if (compliance != address(0)) {
            IConfidentialCompliance(compliance).destroyed(from, actual);
        }
    }

    /// @notice Confidential allowance-based transfer that enforces the same T-REX identity/
    ///         freeze/pause/modular-compliance gating as {confidentialTransfer} — without this
    ///         override the base {ConfidentialERC20.confidentialTransferFrom} would let any
    ///         approved spender move encrypted tokens between two addresses with zero compliance
    ///         enforcement, bypassing every check a direct {confidentialTransfer} is subject to.
    function confidentialTransferFrom(
        address from,
        address to,
        einput encryptedAmount,
        bytes calldata inputProof
    ) public virtual override returns (euint64 transferred) {
        _checkTransfer(from, to);
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        transferred = _transferFrom(from, to, amount);
        if (compliance != address(0)) {
            IConfidentialCompliance(compliance).transferred(from, to, transferred);
        }
    }

    /// @notice Forced transfer used by the registry to execute court orders
    ///         or regulatory interventions; bypasses pause/freeze but still
    ///         requires the recipient to be KYC-verified.
    function forcedTransfer(
        address from,
        address to,
        einput encryptedAmount,
        bytes calldata inputProof
    ) external onlyAgent returns (euint64 transferred) {
        if (identityRegistry != address(0)
            && !IIdentityRegistryMinimal(identityRegistry).isVerified(to)) {
            revert RecipientNotVerified();
        }
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        transferred = _transfer(from, to, amount);
        // Bypassing _checkTransfer (pause/freeze) is intentional for a regulatory override, but
        // the compliance module's bookkeeping (cumulative holdings/investor counts/transfer
        // limits) must still be told the transfer happened — real T-REX's Token.forcedTransfer
        // (contracts/lib/erc3643/contracts/token/Token.sol) does the same, otherwise the module's
        // internal state silently drifts from on-chain reality after every forced transfer.
        if (compliance != address(0)) {
            IConfidentialCompliance(compliance).transferred(from, to, transferred);
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────

    function _checkTransfer(address from, address to) internal view {
        if (paused) revert TransferPaused();
        if (isFrozen[from] || isFrozen[to]) revert AddressIsFrozen();
        if (identityRegistry != address(0)) {
            IIdentityRegistryMinimal ir = IIdentityRegistryMinimal(identityRegistry);
            if (!ir.isVerified(from)) revert SenderNotVerified();
            if (!ir.isVerified(to))   revert RecipientNotVerified();
        }
        if (compliance != address(0)
            && !IConfidentialCompliance(compliance).canTransfer(from, to)) {
            revert ComplianceRejected();
        }
    }
}
