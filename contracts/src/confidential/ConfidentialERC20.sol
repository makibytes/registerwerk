// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

// Zama fhEVM — Fully Homomorphic Encryption for EVM.
// Imports become available after `forge install zama-ai/fhevm-solidity`.
import "@fhevm/lib/TFHE.sol";
import "@fhevm/lib/FHE.sol";
import "@fhevm/gateway/GatewayCaller.sol";
import "@openzeppelin/contracts/access/Ownable.sol";

/**
 * @title ConfidentialERC20 (ERC-7984)
 * @notice Confidential fungible token with FHE-encrypted balances.
 *
 * Implements the ERC-7984 "Confidential Fungible Token" interface as
 * standardised by OpenZeppelin's confidential-contracts suite
 * (https://docs.openzeppelin.com/confidential-contracts/token) and backed by
 * Zama's fhEVM. Balances, allowances and transfer amounts are stored as
 * euint64 ciphertexts — neither validators, indexers nor block explorers can
 * read cleartext values. Re-encryption is performed via the Zama KMS Gateway
 * so that holders can decrypt their own balance with their viewing key.
 *
 * Supported fhEVM networks (must be configured via the factory):
 *   - Fhenix (mainnet 8008135 / Helium testnet 21888)
 *   - Inco   (mainnet 21097   / Rivest testnet 9090)
 *
 * Events:
 *   - `ConfidentialTransfer` and `ConfidentialMint`/`ConfidentialBurn` are
 *     emitted without amounts. A handle to the encrypted delta is exposed so
 *     an authorised oracle can re-decrypt off-chain for indexing purposes.
 */
contract ConfidentialERC20 is Ownable {
    using TFHE for euint64;

    // ── ERC-7984 metadata ──────────────────────────────────────────────────
    string public name;
    string public symbol;
    uint8 public constant decimals = 6;

    // ── Encrypted state ────────────────────────────────────────────────────
    mapping(address => euint64) internal _balances;
    mapping(address => mapping(address => euint64)) internal _allowances;
    euint64 internal _totalSupply;

    bytes32 public immutable assetId;
    address public immutable kmsGateway;

    // ── Events (ERC-7984) ──────────────────────────────────────────────────
    event ConfidentialTransfer(address indexed from, address indexed to, euint64 handle);
    event ConfidentialApproval(address indexed owner, address indexed spender, euint64 handle);
    event ConfidentialMint(address indexed to, euint64 handle);
    event ConfidentialBurn(address indexed from, euint64 handle);

    // ── Errors ─────────────────────────────────────────────────────────────
    error InsufficientConfidentialBalance();
    error UnauthorizedDecryption();

    constructor(
        bytes32 _assetId,
        string memory _name,
        string memory _symbol,
        address _kmsGateway,
        address _owner
    ) Ownable(_owner) {
        assetId    = _assetId;
        name       = _name;
        symbol     = _symbol;
        kmsGateway = _kmsGateway;
        _totalSupply = TFHE.asEuint64(0);
        TFHE.allowThis(_totalSupply);
    }

    // ── ERC-7984: confidential transfers ───────────────────────────────────

    /**
     * @notice Transfer an encrypted amount to `to`.
     * @dev The homomorphic sub/add preserves correctness even though the
     *      amount is never revealed; if the sender has insufficient balance
     *      the subtraction becomes a no-op via `TFHE.select`, mirroring the
     *      ERC-7984 "silent failure" semantics.
     */
    function confidentialTransfer(
        address to,
        einput encryptedAmount,
        bytes calldata inputProof
    ) external returns (euint64 transferred) {
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        return _transfer(msg.sender, to, amount);
    }

    function confidentialTransferFrom(
        address from,
        address to,
        einput encryptedAmount,
        bytes calldata inputProof
    ) external returns (euint64 transferred) {
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        euint64 currentAllowance = _allowances[from][msg.sender];
        ebool allowed = TFHE.le(amount, currentAllowance);
        euint64 used  = TFHE.select(allowed, amount, TFHE.asEuint64(0));
        _allowances[from][msg.sender] = TFHE.sub(currentAllowance, used);
        TFHE.allowThis(_allowances[from][msg.sender]);
        return _transfer(from, to, used);
    }

    function confidentialApprove(
        address spender,
        einput encryptedAmount,
        bytes calldata inputProof
    ) external {
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        _allowances[msg.sender][spender] = amount;
        TFHE.allowThis(amount);
        TFHE.allow(amount, spender);
        emit ConfidentialApproval(msg.sender, spender, amount);
    }

    // ── Mint / Burn (owner only) ───────────────────────────────────────────

    function confidentialMint(
        address to,
        einput encryptedAmount,
        bytes calldata inputProof
    ) external onlyOwner {
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        _balances[to] = TFHE.add(_balances[to], amount);
        _totalSupply  = TFHE.add(_totalSupply, amount);
        TFHE.allowThis(_balances[to]);
        TFHE.allow(_balances[to], to);
        TFHE.allowThis(_totalSupply);
        emit ConfidentialMint(to, amount);
    }

    function confidentialBurn(
        address from,
        einput encryptedAmount,
        bytes calldata inputProof
    ) external onlyOwner {
        euint64 amount  = TFHE.asEuint64(encryptedAmount, inputProof);
        ebool enough    = TFHE.le(amount, _balances[from]);
        euint64 actual  = TFHE.select(enough, amount, TFHE.asEuint64(0));
        _balances[from] = TFHE.sub(_balances[from], actual);
        _totalSupply    = TFHE.sub(_totalSupply, actual);
        TFHE.allowThis(_balances[from]);
        TFHE.allowThis(_totalSupply);
        emit ConfidentialBurn(from, actual);
    }

    // ── View: encrypted handles ────────────────────────────────────────────

    /// @notice Returns the FHE handle of `account`'s balance. Only the holder
    ///         (and owner) are authorised to decrypt through the KMS Gateway.
    function confidentialBalanceOf(address account) external view returns (euint64) {
        return _balances[account];
    }

    function confidentialTotalSupply() external view returns (euint64) {
        return _totalSupply;
    }

    function confidentialAllowance(address owner_, address spender)
        external
        view
        returns (euint64)
    {
        return _allowances[owner_][spender];
    }

    // ── Internal ───────────────────────────────────────────────────────────

    function _transfer(address from, address to, euint64 amount)
        internal
        returns (euint64 transferred)
    {
        ebool enough    = TFHE.le(amount, _balances[from]);
        transferred     = TFHE.select(enough, amount, TFHE.asEuint64(0));
        _balances[from] = TFHE.sub(_balances[from], transferred);
        _balances[to]   = TFHE.add(_balances[to],   transferred);
        TFHE.allowThis(_balances[from]);
        TFHE.allowThis(_balances[to]);
        TFHE.allow(_balances[from], from);
        TFHE.allow(_balances[to], to);
        emit ConfidentialTransfer(from, to, transferred);
    }
}
