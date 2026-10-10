// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

// Zama FHEVM — Fully Homomorphic Encryption for EVM (@fhevm/solidity 0.14, the zama-ai/fhevm monorepo's
// `library-solidity`, vendored as the `lib/fhevm` submodule; the encrypted types come from `lib/encrypted-types`).
import {FHE, euint64, ebool, externalEuint64} from "@fhevm/solidity/lib/FHE.sol";
import {CoprocessorConfig} from "@fhevm/solidity/lib/Impl.sol";
import "@openzeppelin/contracts/access/Ownable.sol";
import "../documents/EwpgDocumentManagement.sol";

/**
 * @title ConfidentialERC20 (ERC-7984)
 * @notice Confidential fungible token with FHE-encrypted balances.
 *
 * Implements the ERC-7984 "Confidential Fungible Token" interface as
 * standardised by OpenZeppelin's confidential-contracts suite
 * (https://docs.openzeppelin.com/confidential-contracts/token) and backed by
 * Zama's FHEVM (the `FHE` library of fhevm-solidity 0.14, vendored under
 * `lib/fhevm/library-solidity` from the zama-ai/fhevm monorepo). Balances, allowances and transfer
 * amounts are stored as euint64 ciphertexts — neither validators, indexers
 * nor block explorers can read cleartext values.
 *
 * @dev The FHEVM host-contract addresses (ACL, the FHEVMExecutor "coprocessor" and the
 * KMSVerifier) are supplied by the deploying factory at construction time via
 * {FhevmInfra}, NOT hardcoded per network. This is deliberate: the library's
 * `ZamaConfig` knows Ethereum, Polygon, their testnets and the local network
 * only. Injecting addresses keeps deployments independent of library defaults
 * and avoids contract redeployment when infrastructure addresses change. This
 * contract does not assume T-REX's addresses match Ethereum's. (The Gateway of
 * FHEVM before 0.9 is gone: it lives on its own chain and a host-chain contract no
 * longer talks to it.)
 *
 * Two decryption paths exist, both real here:
 *   - User decryption (a holder reading their OWN balance) is off-chain:
 *     the holder's wallet signs an EIP-712 request and calls the Zama
 *     Relayer's `userDecrypt`; the contract's only job is the `FHE.allow`
 *     ACL grant already present on every mutation below.
 *   - Public decryption (see {requestSupplyDisclosure} / {fulfillSupplyDisclosure})
 *     is verified on-chain: the owner marks a handle publicly decryptable, anyone
 *     fetches the cleartext and the KMS decryption proof from the Zama Relayer
 *     (`publicDecrypt`) and submits both; the contract checks the KMS signatures
 *     with `FHE.checkSignatures` before recording the value — used here for a
 *     regulator-triggered total-supply disclosure, since eWpG/MiCAR oversight
 *     cannot rely on a holder's own signature to see aggregate figures.
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
 *   once a handle has been `FHE.allow`ed to an address, that grant is permanent for that specific
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
contract ConfidentialERC20 is Ownable, EwpgDocumentManagement {
    /// @dev The three FHEVM host-contract addresses a deploying factory must supply — see the
    ///      class-level note on why these are injected rather than hardcoded per network.
    struct FhevmInfra {
        address aclAddress;
        address coprocessorAddress;
        address kmsVerifierAddress;
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

    // ── Supply disclosure (public decryption) ───────────────────────────────
    /// @notice requestId => the total-supply handle that was made publicly decryptable for it.
    mapping(uint256 => euint64) public supplyDisclosureHandle;
    mapping(uint256 => bool) public supplyDisclosureFulfilled;
    /// @notice The supply of the NEWEST fulfilled disclosure request (see {lastDisclosureRequestId}).
    uint64 public lastDisclosedSupply;
    uint256 public lastDisclosureRequestId;
    uint256 private _nextDisclosureRequestId;

    // ── Events (ERC-7984) ──────────────────────────────────────────────────
    event ConfidentialTransfer(address indexed from, address indexed to, euint64 handle);
    event ConfidentialApproval(address indexed owner, address indexed spender, euint64 handle);
    event ConfidentialMint(address indexed to, euint64 handle);
    event ConfidentialBurn(address indexed from, euint64 handle);
    event SupplyDisclosureRequested(uint256 indexed requestId, euint64 handle);
    event SupplyDisclosureFulfilled(uint256 indexed requestId, uint64 supply);

    // ── Errors ─────────────────────────────────────────────────────────────
    error InsufficientConfidentialBalance();
    error UnauthorizedDecryption();
    error UnknownDisclosureRequest(uint256 requestId);
    error DisclosureAlreadyFulfilled(uint256 requestId);

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

        FHE.setCoprocessor(CoprocessorConfig({
            ACLAddress: _infra.aclAddress,
            CoprocessorAddress: _infra.coprocessorAddress,
            KMSVerifierAddress: _infra.kmsVerifierAddress
        }));

        // Registerwerk provisions the registry operator + auditor (and, for ERC-3643, the issuer's
        // bound wallet) as viewers from block one, so no post-deploy transaction is needed before
        // the operator/auditor can reconcile the first minted balance.
        for (uint256 i = 0; i < _initialViewers.length; i++) {
            _addViewer(_initialViewers[i]);
        }

        _totalSupply = FHE.asEuint64(0);
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
        FHE.allowThis(_balances[holder]);
        FHE.allow(_balances[holder], holder);
        uint256 len = _viewers.length;
        for (uint256 i = 0; i < len; i++) {
            FHE.allow(_balances[holder], _viewers[i]);
        }
    }

    /// @dev Same rationale as {_grantBalanceAcl}, for `_totalSupply` — called after every mint/burn
    ///      so operator/auditor viewers can `userDecrypt` supply directly, in addition to the
    ///      existing {requestSupplyDisclosure} on-chain oracle path.
    function _grantSupplyAcl() internal {
        FHE.allowThis(_totalSupply);
        uint256 len = _viewers.length;
        for (uint256 i = 0; i < len; i++) {
            FHE.allow(_totalSupply, _viewers[i]);
        }
    }

    /// @dev Grants every registered viewer decrypt rights on the ciphertext handle EXPOSED IN
    ///      A TRANSFER/MINT/BURN EVENT — a fresh ciphertext distinct from `_balances`/
    ///      `_totalSupply` (which get their own grants via {_grantBalanceAcl}/{_grantSupplyAcl}),
    ///      so without this call the handle the class-level docs describe as decryptable "for
    ///      indexing purposes" was never actually `FHE.allow`ed to anyone and any off-chain
    ///      viewer decrypt of it (e.g. Travel Rule screening, reconciliation) would be rejected.
    function _grantHandleAcl(euint64 handle) internal {
        FHE.allowThis(handle);
        uint256 len = _viewers.length;
        for (uint256 i = 0; i < len; i++) {
            FHE.allow(handle, _viewers[i]);
        }
    }

    // ── ERC-7984: confidential transfers ───────────────────────────────────

    /**
     * @notice Transfer an encrypted amount to `to`.
     * @dev The homomorphic sub/add preserves correctness even though the
     *      amount is never revealed; if the sender has insufficient balance
     *      the subtraction becomes a no-op via `FHE.select`, mirroring the
     *      ERC-7984 "silent failure" semantics.
     */
    function confidentialTransfer(
        address to,
        externalEuint64 encryptedAmount,
        bytes calldata inputProof
    ) public virtual returns (euint64 transferred) {
        euint64 amount = FHE.fromExternal(encryptedAmount, inputProof);
        return _transfer(msg.sender, to, amount);
    }

    function confidentialTransferFrom(
        address from,
        address to,
        externalEuint64 encryptedAmount,
        bytes calldata inputProof
    ) public virtual returns (euint64 transferred) {
        euint64 amount = FHE.fromExternal(encryptedAmount, inputProof);
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
        ebool allowed = FHE.le(amount, currentAllowance);
        euint64 gated = FHE.select(allowed, amount, FHE.asEuint64(0));
        // Decrement the allowance by what _transfer actually moved (it independently re-gates
        // by from's balance), not by `gated` — otherwise a transfer that moves zero tokens
        // because the balance check failed would still burn the spender's allowance.
        transferred = _transfer(from, to, gated);
        _allowances[from][msg.sender] = FHE.sub(currentAllowance, transferred);
        FHE.allowThis(_allowances[from][msg.sender]);
        // Re-grant the spender's own ACL on the new allowance handle — FHE.sub produces a
        // fresh ciphertext with no carried-over ACL, so without this the spender is locked out
        // of decrypting their own remaining allowance after the very first transfer (the same
        // bug class already fixed for confidentialBurn above, reintroduced here).
        FHE.allow(_allowances[from][msg.sender], msg.sender);
    }

    function confidentialApprove(
        address spender,
        externalEuint64 encryptedAmount,
        bytes calldata inputProof
    ) external {
        euint64 amount = FHE.fromExternal(encryptedAmount, inputProof);
        _allowances[msg.sender][spender] = amount;
        FHE.allowThis(amount);
        FHE.allow(amount, spender);
        emit ConfidentialApproval(msg.sender, spender, amount);
    }

    // ── Mint / Burn (owner only) ───────────────────────────────────────────

    function confidentialMint(
        address to,
        externalEuint64 encryptedAmount,
        bytes calldata inputProof
    ) public virtual onlyOwner {
        euint64 amount = FHE.fromExternal(encryptedAmount, inputProof);
        _balances[to] = FHE.add(_balances[to], amount);
        _totalSupply  = FHE.add(_totalSupply, amount);
        _grantBalanceAcl(to);
        _grantSupplyAcl();
        _grantHandleAcl(amount);
        emit ConfidentialMint(to, amount);
    }

    function confidentialBurn(
        address from,
        externalEuint64 encryptedAmount,
        bytes calldata inputProof
    ) public virtual onlyOwner {
        euint64 amount  = FHE.fromExternal(encryptedAmount, inputProof);
        ebool enough    = FHE.le(amount, _balances[from]);
        euint64 actual  = FHE.select(enough, amount, FHE.asEuint64(0));
        _balances[from] = FHE.sub(_balances[from], actual);
        _totalSupply    = FHE.sub(_totalSupply, actual);
        // Previously this only re-armed FHE.allowThis on the new handles and never re-granted
        // the holder (or any viewer) `FHE.allow(_balances[from], from)` — since sub() produces a
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

    // ── Public decryption: regulator-triggered supply disclosure ────────────

    /**
     * @notice Marks the CURRENT total-supply handle publicly decryptable and opens a disclosure
     *         request for it — the on-chain-verified (not off-chain-signed) decryption path, for
     *         MiCAR/eWpG disclosure obligations that need a value the CONTRACT itself published,
     *         not one a single holder happened to reveal. Anyone may then fetch the cleartext and
     *         the KMS decryption proof from the Zama Relayer (`publicDecrypt`) and pass them to
     *         {fulfillSupplyDisclosure}.
     * @dev Owner-gated: this is a registry/compliance action, not a public one. The request is
     *      bound to the handle as it is NOW: later mints or burns create a new supply handle and
     *      do not invalidate (or leak through) an open request.
     */
    function requestSupplyDisclosure() external onlyOwner returns (uint256 requestId) {
        euint64 handle = FHE.makePubliclyDecryptable(_totalSupply);
        requestId = ++_nextDisclosureRequestId;
        supplyDisclosureHandle[requestId] = handle;
        emit SupplyDisclosureRequested(requestId, handle);
    }

    /**
     * @notice Records the cleartext of a requested supply handle once the KMS signatures over it
     *         verify. Permissionless on purpose: the proof, not the caller, is the authority, so
     *         the operator, an auditor or a bot can relay it.
     * @param abiEncodedCleartexts The ABI-encoding of the decrypted `uint64` supply.
     * @param decryptionProof      The KMS public-decryption proof (signatures + extra data).
     */
    function fulfillSupplyDisclosure(
        uint256 requestId,
        bytes calldata abiEncodedCleartexts,
        bytes calldata decryptionProof
    ) external {
        euint64 handle = supplyDisclosureHandle[requestId];
        if (!FHE.isInitialized(handle)) revert UnknownDisclosureRequest(requestId);
        if (supplyDisclosureFulfilled[requestId]) revert DisclosureAlreadyFulfilled(requestId);

        bytes32[] memory handlesList = new bytes32[](1);
        handlesList[0] = FHE.toBytes32(handle);
        // Reverts unless enough registered KMS signers signed exactly these handles and cleartexts.
        FHE.checkSignatures(handlesList, abiEncodedCleartexts, decryptionProof);

        uint64 decryptedSupply = abi.decode(abiEncodedCleartexts, (uint64));
        supplyDisclosureFulfilled[requestId] = true;
        // Fulfilment is permissionless and the KMS proof of an older request stays valid, so it can
        // arrive after a newer one: an older request is still recorded (flag + event) but must never
        // overwrite the figure of a newer request as "the last disclosed supply".
        if (requestId > lastDisclosureRequestId) {
            lastDisclosedSupply = decryptedSupply;
            lastDisclosureRequestId = requestId;
        }
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
        ebool enough    = FHE.le(amount, _balances[from]);
        transferred     = FHE.select(enough, amount, FHE.asEuint64(0));
        _balances[from] = FHE.sub(_balances[from], transferred);
        _balances[to]   = FHE.add(_balances[to],   transferred);
        _grantBalanceAcl(from);
        _grantBalanceAcl(to);
        _grantHandleAcl(transferred);
        emit ConfidentialTransfer(from, to, transferred);
    }
}
