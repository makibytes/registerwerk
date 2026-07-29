// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

// Zama fhEVM — Fully Homomorphic Encryption for EVM.
// Imports become available after `forge install zama-ai/fhevm-solidity`.
import "@fhevm/lib/TFHE.sol";
import "@fhevm/gateway/GatewayCaller.sol";
import "@fhevm/gateway/lib/Gateway.sol";
import "@openzeppelin/contracts/access/Ownable.sol";
import "../documents/EwpgDocumentManagement.sol";

/**
 * @title ConfidentialERC20 (ERC-7984)
 * @notice Confidential fungible token with FHE-encrypted balances.
 *
 * Implements the ERC-7984 "Confidential Fungible Token" interface as
 * standardised by OpenZeppelin's confidential-contracts suite
 * (https://docs.openzeppelin.com/confidential-contracts/token) and backed by
 * Zama's fhEVM (the legacy TFHE.sol / Gateway API vendored under
 * `lib/fhevm` — zama-ai/fhevm-solidity). Balances, allowances and transfer
 * amounts are stored as euint64 ciphertexts — neither validators, indexers
 * nor block explorers can read cleartext values.
 *
 * @dev FHEVM/Gateway infrastructure addresses (ACL, TFHEExecutor, FHEPayment,
 * KMSVerifier, Gateway) are supplied by the deploying factory at construction
 * time via {FhevmInfra}, NOT hardcoded per network. This is deliberate: the
 * vendored `ZamaFHEVMConfig`/`ZamaGatewayConfig` libraries only have real
 * addresses for Sepolia — `getEthereumConfig()` is an upstream `/// TODO`
 * stub — and Zama's own mainnet host-contract addresses are still being
 * finalised (target Q3 2026) and are governance-upgradeable even once live
 * (see SETUP-42 on the Zama community forum). Hardcoding them here would
 * either compile against a non-existent mainnet address or need a contract
 * redeploy on every governance upgrade. T-REX Network announced in March
 * 2026 that Zama is becoming the confidentiality layer for the T-REX Ledger
 * — the same injectable-address pattern applies there once T-REX publishes
 * its own FHEVM infrastructure addresses; this contract does not assume
 * T-REX's addresses match Ethereum's.
 *
 * Two decryption paths exist, both real here:
 *   - User decryption (a holder reading their OWN balance) is off-chain:
 *     the holder's wallet signs an EIP-712 request and calls the Zama
 *     Relayer's `userDecrypt`; the contract's only job is the `TFHE.allow`
 *     ACL grant already present on every mutation below.
 *   - Public/oracle decryption (see {requestSupplyDisclosure}) is on-chain:
 *     the contract itself asks the Gateway to decrypt a handle and receive
 *     the cleartext back via a signed callback — used here for a
 *     regulator-triggered total-supply disclosure, since eWpG/MiCAR
 *     oversight cannot rely on a holder's own signature to see aggregate
 *     figures.
 *
 * Events:
 *   - `ConfidentialTransfer` and `ConfidentialMint`/`ConfidentialBurn` are
 *     emitted without amounts. A handle to the encrypted delta is exposed so
 *     an authorised viewer can re-decrypt off-chain (via the Relayer) for
 *     indexing purposes.
 *
 * Viewer ACL model (who besides the holder may decrypt a balance):
 *   Registerwerk itself is the confidential-token client — issuers, investors, the registry
 *   operator, and an auditor role all interact with encrypted balances THROUGH Registerwerk, not
 *   around it. Zama's ACL grants are additive and per-ciphertext-handle (there is no "revoke" —
 *   once a handle has been `TFHE.allow`ed to an address, that grant is permanent for that specific
 *   handle; only handles minted/transferred AFTER a viewer is removed stop including them). This
 *   contract uses that primitive to isolate holders from each other while giving Registerwerk's
 *   registry roles full visibility:
 *     - Every holder is granted decrypt rights on their OWN balance handle only — never another
 *       holder's. This is the per-investor isolation: investor A can never decrypt investor B's
 *       balance, because A is never `allow`ed on B's handle.
 *     - {addViewer}/{removeViewer} (owner-gated) maintain a small set of "global viewer" addresses
 *       — the registry operator, an auditor, and (for issuer-facing visibility) the issuer's bound
 *       wallet — granted decrypt rights on EVERY handle (balance + total supply) at the moment it
 *       is minted or updated. See {_grantBalanceAcl}/{_grantSupplyAcl}.
 */
contract ConfidentialERC20 is Ownable, EwpgDocumentManagement, GatewayCaller {
    using TFHE for euint64;

    /// @dev The five addresses a deploying factory must supply — see the class-level note on
    ///      why these are injected rather than hardcoded per network.
    struct FhevmInfra {
        address aclAddress;
        address tfheExecutorAddress;
        address fhePaymentAddress;
        address kmsVerifierAddress;
        address gatewayAddress;
    }

    // ── Viewer ACL registry ─────────────────────────────────────────────────
    mapping(address => bool) public isViewer;
    address[] private _viewers;

    event ViewerAdded(address indexed viewer);
    event ViewerRemoved(address indexed viewer);

    // ── ERC-7984 metadata ──────────────────────────────────────────────────
    string public name;
    string public symbol;
    uint8 public constant decimals = 6;

    // ── Encrypted state ────────────────────────────────────────────────────
    mapping(address => euint64) internal _balances;
    mapping(address => mapping(address => euint64)) internal _allowances;
    euint64 internal _totalSupply;

    bytes32 public immutable assetId;
    address public immutable gatewayAddress;

    // ── Supply disclosure (public/oracle decryption) ────────────────────────
    mapping(uint256 => bool) public supplyDisclosureFulfilled;
    uint64 public lastDisclosedSupply;
    uint256 public lastDisclosureRequestId;

    // ── Events (ERC-7984) ──────────────────────────────────────────────────
    event ConfidentialTransfer(address indexed from, address indexed to, euint64 handle);
    event ConfidentialApproval(address indexed owner, address indexed spender, euint64 handle);
    event ConfidentialMint(address indexed to, euint64 handle);
    event ConfidentialBurn(address indexed from, euint64 handle);
    event SupplyDisclosureRequested(uint256 indexed requestId);
    event SupplyDisclosureFulfilled(uint256 indexed requestId, uint64 supply);

    // ── Errors ─────────────────────────────────────────────────────────────
    error InsufficientConfidentialBalance();
    error UnauthorizedDecryption();

    constructor(
        bytes32 _assetId,
        string memory _name,
        string memory _symbol,
        FhevmInfra memory _infra,
        address[] memory _initialViewers,
        address _owner
    ) Ownable(_owner) {
        assetId    = _assetId;
        name       = _name;
        symbol     = _symbol;
        gatewayAddress = _infra.gatewayAddress;

        TFHE.setFHEVM(FHEVMConfigStruct({
            ACLAddress: _infra.aclAddress,
            TFHEExecutorAddress: _infra.tfheExecutorAddress,
            FHEPaymentAddress: _infra.fhePaymentAddress,
            KMSVerifierAddress: _infra.kmsVerifierAddress
        }));
        Gateway.setGateway(_infra.gatewayAddress);

        // Registerwerk provisions the registry operator + auditor (and, for ERC-3643, the issuer's
        // bound wallet) as viewers from block one, so no post-deploy transaction is needed before
        // the operator/auditor can reconcile the first minted balance.
        for (uint256 i = 0; i < _initialViewers.length; i++) {
            _addViewer(_initialViewers[i]);
        }

        _totalSupply = TFHE.asEuint64(0);
        _grantSupplyAcl();
    }

    // ── Viewer management (owner-gated) ─────────────────────────────────────

    /**
     * @notice Grants `viewer` decrypt rights on every holder's balance handle and on total
     *         supply, from this point forward. Intended for the registry operator, an auditor
     *         role, and (on {ConfidentialERC3643}) the issuer's bound wallet.
     * @dev Zama's ACL is additive and per-handle: this does not retroactively grant access to
     *      ciphertext handles that predate the call and are never touched again (e.g. a balance
     *      that never moves again after this viewer is added). In practice every balance is
     *      re-granted on its next mutation via {_grantBalanceAcl}, so a viewer added before any
     *      further activity sees it; a viewer wanting HISTORICAL handles decrypted needs the
     *      holder (or another already-authorised viewer) to request that decryption directly.
     */
    function addViewer(address viewer) public onlyOwner {
        _addViewer(viewer);
    }

    /**
     * @notice Stops granting `viewer` decrypt rights on FUTURE balance mutations.
     * @dev Does NOT revoke already-granted access to existing ciphertext handles — Zama's ACL has
     *      no revoke primitive. A removed viewer retains the ability to decrypt any handle they
     *      were already allowed on until that holder's balance next changes (which grants a new
     *      handle without them). Document this limitation to callers rather than implying instant
     *      revocation.
     */
    function removeViewer(address viewer) external onlyOwner {
        if (!isViewer[viewer]) {
            return;
        }
        isViewer[viewer] = false;
        uint256 len = _viewers.length;
        for (uint256 i = 0; i < len; i++) {
            if (_viewers[i] == viewer) {
                _viewers[i] = _viewers[len - 1];
                _viewers.pop();
                break;
            }
        }
        emit ViewerRemoved(viewer);
    }

    /// @notice Returns the current set of global viewer addresses.
    function viewers() external view returns (address[] memory) {
        return _viewers;
    }

    function _addViewer(address viewer) internal {
        if (viewer == address(0) || isViewer[viewer]) {
            return;
        }
        isViewer[viewer] = true;
        _viewers.push(viewer);
        emit ViewerAdded(viewer);
    }

    /// @dev Grants the holder decrypt rights on their OWN balance handle plus every registered
    ///      viewer — called after every mutation of `_balances[holder]`, since a homomorphic
    ///      op (add/sub/select) produces a brand-new ciphertext handle with no ACL grants of
    ///      its own; prior grants on the OLD handle do not carry over.
    function _grantBalanceAcl(address holder) internal {
        TFHE.allowThis(_balances[holder]);
        TFHE.allow(_balances[holder], holder);
        uint256 len = _viewers.length;
        for (uint256 i = 0; i < len; i++) {
            TFHE.allow(_balances[holder], _viewers[i]);
        }
    }

    /// @dev Same rationale as {_grantBalanceAcl}, for `_totalSupply` — called after every mint/burn
    ///      so operator/auditor viewers can `userDecrypt` supply directly, in addition to the
    ///      existing {requestSupplyDisclosure} on-chain oracle path.
    function _grantSupplyAcl() internal {
        TFHE.allowThis(_totalSupply);
        uint256 len = _viewers.length;
        for (uint256 i = 0; i < len; i++) {
            TFHE.allow(_totalSupply, _viewers[i]);
        }
    }

    /// @dev Grants every registered viewer decrypt rights on the ciphertext handle EXPOSED IN
    ///      A TRANSFER/MINT/BURN EVENT — a fresh ciphertext distinct from `_balances`/
    ///      `_totalSupply` (which get their own grants via {_grantBalanceAcl}/{_grantSupplyAcl}),
    ///      so without this call the handle the class-level docs describe as decryptable "for
    ///      indexing purposes" was never actually `TFHE.allow`ed to anyone and any off-chain
    ///      viewer decrypt of it (e.g. Travel Rule screening, reconciliation) would be rejected.
    function _grantHandleAcl(euint64 handle) internal {
        TFHE.allowThis(handle);
        uint256 len = _viewers.length;
        for (uint256 i = 0; i < len; i++) {
            TFHE.allow(handle, _viewers[i]);
        }
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
    ) public virtual returns (euint64 transferred) {
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        return _transfer(msg.sender, to, amount);
    }

    function confidentialTransferFrom(
        address from,
        address to,
        einput encryptedAmount,
        bytes calldata inputProof
    ) public virtual returns (euint64 transferred) {
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        return _transferFrom(from, to, amount);
    }

    /// @dev Extracted so {ConfidentialERC3643} can override the public entry point to add
    ///      compliance gating (identity/freeze/pause/canTransfer) before the allowance-gated
    ///      move, exactly like {confidentialTransfer} does for the non-allowance path, without
    ///      duplicating the allowance bookkeeping below.
    function _transferFrom(address from, address to, euint64 amount)
        internal
        returns (euint64 transferred)
    {
        euint64 currentAllowance = _allowances[from][msg.sender];
        ebool allowed = TFHE.le(amount, currentAllowance);
        euint64 gated = TFHE.select(allowed, amount, TFHE.asEuint64(0));
        // Decrement the allowance by what _transfer actually moved (it independently re-gates
        // by from's balance), not by `gated` — otherwise a transfer that moves zero tokens
        // because the balance check failed would still burn the spender's allowance.
        transferred = _transfer(from, to, gated);
        _allowances[from][msg.sender] = TFHE.sub(currentAllowance, transferred);
        TFHE.allowThis(_allowances[from][msg.sender]);
        // Re-grant the spender's own ACL on the new allowance handle — TFHE.sub produces a
        // fresh ciphertext with no carried-over ACL, so without this the spender is locked out
        // of decrypting their own remaining allowance after the very first transfer (the same
        // bug class already fixed for confidentialBurn above, reintroduced here).
        TFHE.allow(_allowances[from][msg.sender], msg.sender);
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
    ) public virtual onlyOwner {
        euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
        _balances[to] = TFHE.add(_balances[to], amount);
        _totalSupply  = TFHE.add(_totalSupply, amount);
        _grantBalanceAcl(to);
        _grantSupplyAcl();
        _grantHandleAcl(amount);
        emit ConfidentialMint(to, amount);
    }

    function confidentialBurn(
        address from,
        einput encryptedAmount,
        bytes calldata inputProof
    ) public virtual onlyOwner {
        euint64 amount  = TFHE.asEuint64(encryptedAmount, inputProof);
        ebool enough    = TFHE.le(amount, _balances[from]);
        euint64 actual  = TFHE.select(enough, amount, TFHE.asEuint64(0));
        _balances[from] = TFHE.sub(_balances[from], actual);
        _totalSupply    = TFHE.sub(_totalSupply, actual);
        // Previously this only re-armed TFHE.allowThis on the new handles and never re-granted
        // the holder (or any viewer) `TFHE.allow(_balances[from], from)` — since sub() produces a
        // brand-new ciphertext handle, the holder would have been permanently locked out of their
        // own post-burn balance. Fixed by routing through the same _grantBalanceAcl/_grantSupplyAcl
        // helpers every other mutation uses.
        _grantBalanceAcl(from);
        _grantSupplyAcl();
        _grantHandleAcl(actual);
        emit ConfidentialBurn(from, actual);
    }

    // ── View: encrypted handles ────────────────────────────────────────────

    /// @notice Returns the FHE handle of `account`'s balance. Only the holder
    ///         (and owner) are authorised to decrypt through the Zama Relayer's
    ///         off-chain `userDecrypt` (EIP-712-signed) — see the class-level note.
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

    // ── Public/oracle decryption: regulator-triggered supply disclosure ─────

    /**
     * @notice Requests the Gateway decrypt the current total supply — the
     *         on-chain (not off-chain-signed) decryption path, for MiCAR/eWpG
     *         disclosure obligations that need a value the CONTRACT itself
     *         published, not one a single holder happened to reveal.
     * @dev Owner-gated: this is a registry/compliance action, not a public one.
     */
    function requestSupplyDisclosure() external onlyOwner returns (uint256 requestId) {
        uint256[] memory cts = new uint256[](1);
        cts[0] = Gateway.toUint256(_totalSupply);
        requestId = Gateway.requestDecryption(
            cts, this.callbackSupplyDisclosure.selector, 0, block.timestamp + 1 days, false
        );
        emit SupplyDisclosureRequested(requestId);
    }

    /// @dev Called back by the Gateway only — `onlyGateway` (from {GatewayCaller}) verifies
    ///      `msg.sender` is the configured Gateway contract, which itself only forwards a
    ///      result after the KMS's threshold-decryption signatures have been checked.
    function callbackSupplyDisclosure(uint256 requestId, uint64 decryptedSupply) external onlyGateway {
        supplyDisclosureFulfilled[requestId] = true;
        lastDisclosedSupply = decryptedSupply;
        lastDisclosureRequestId = requestId;
        emit SupplyDisclosureFulfilled(requestId, decryptedSupply);
    }

    // ── Document admin guard ──────────────────────────────────────────────────

    function _requireDocumentAdmin() internal view override {
        require(msg.sender == owner(), "ConfidentialERC20: caller is not owner");
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
        _grantBalanceAcl(from);
        _grantBalanceAcl(to);
        _grantHandleAcl(transferred);
        emit ConfidentialTransfer(from, to, transferred);
    }
}
