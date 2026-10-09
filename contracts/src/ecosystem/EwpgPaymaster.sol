// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {IEntryPoint, IPaymaster, PackedUserOperation} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import {ERC4337Utils} from "@openzeppelin/contracts/account/utils/draft-ERC4337Utils.sol";
import {ECDSA} from "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import {MessageHashUtils} from "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import {SlotDerivation} from "@openzeppelin/contracts/utils/SlotDerivation.sol";
import {TransientSlot} from "@openzeppelin/contracts/utils/TransientSlot.sol";
import "./RegisterwerkGated.sol";
import "./interfaces/IPermissionOracle.sol";

/// @title EwpgPaymaster
/// @notice Reference marketplace dApp: an ERC-4337 *verifying* paymaster that sponsors gas for
///         verified Registerwerk customers, funded per policy by the operator or by an issuer
///         (see `docs/platform/account-abstraction.md`).
///
/// @dev Deployed against EntryPoint v0.8 (`ERC4337Utils.ENTRYPOINT_V08`) — chosen for native
///      EIP-7702 support. Because a 7702-delegated EOA keeps its original address,
///      `userOp.sender` *is* the customer's Registerwerk member-wallet address, so the
///      membership + KYC check below reads directly off `PermissionOracle` for that address.
///
///      **Vouchers.** A UserOperation is only sponsored with a voucher signed by the policy's
///      registered `policySigner` (the Registerwerk backend's voucher issuer, which enforces the
///      policy's scope, active flag and monthly cap off-chain). The voucher travels in
///      `paymasterData = policyId(32) ‖ validUntil(6) ‖ validAfter(6) ‖ maxFeePerGasCap(16) ‖ sig(65)`
///      and signs {getHash} — a paymaster-specific digest over every UserOperation field
///      *except* `paymasterAndData`'s signature bytes (the signature cannot sign the
///      `userOpHash` that contains it), bound to `block.chainid`, this contract, the policy, the
///      validity window and the gas-price cap. A bad signature returns `SIG_VALIDATION_FAILED`
///      (packed with the window), never a revert, per ERC-4337.
///
///      **Budget accounting.** Validation *reserves* `maxCost` out of the policy's unreserved
///      balance (so N ops in one bundle cannot each pass against the same balance), and
///      checks the sender org's cap against `spent + reserved + maxCost` (keyed by
///      `orgOf(sender)`, so fresh wallets of one org cannot multiply the cap). {postOp}
///      never reverts: it books `min(maxCost, actualGasCost + (postOpGasLimit +
///      POST_OP_OVERHEAD_GAS) × feePerGas)` — an upper bound on what the EntryPoint really
///      debits, since v0.7/v0.8 hand postOp the cost *before* postOp's own gas and the
///      unused-gas penalty are added — and refunds the rest of the reservation. The books
///      therefore never exceed the real EntryPoint deposit ({depositSurplus} ≥ 0).
///
///      **Why the reservations are transient (Glamsterdam).** A reservation lives from
///      `validatePaymasterUserOp` to `postOp`, both inside one `handleOps` transaction, and is
///      always back to zero when it ends. As ordinary storage it would be a new slot written in
///      validation and cleared in postOp; under EIP-8037 that costs ~110k gas of *peak* headroom
///      per slot (~220k for the two counters), which the 150k `paymasterVerificationGasLimit` cannot
///      cover — every sponsored operation would fail validation. EIP-1153 transient storage is
///      cleared by the protocol and costs ~100 gas per access. `spentByOrg` stays persistent (it is
///      the cumulative record); its first write per (policy, org) creates a slot, which is why
///      {MIN_POST_OP_GAS_LIMIT} is 150,000.
///
///      **Ownership.** Each policy records its `funder`; only the funder can change the
///      voucher signer or top up, and withdrawals ({withdrawPolicy}) always pay the funder —
///      `paymaster.configure` holders can trigger a refund or deactivate a policy but never
///      redirect funds. `paymaster.configure` is bound to {operatorOrg} (the org operating this
///      instance): a same-slug grant held by any other org reaches neither a policy nor the stake.
///      Registering a policy needs `paymaster.register-policy` (granted by the operator to the
///      orgs it lets sponsor gas), so an unapproved wallet cannot squat a policy id the real
///      funder is about to register. Validation writes storage and reads other contracts, so under
///      ERC-7562 the paymaster must be staked ({addStake}) to be accepted by public bundlers.
contract EwpgPaymaster is RegisterwerkGated, IPaymaster {
    using ERC4337Utils for PackedUserOperation;
    using SlotDerivation for bytes32;
    using TransientSlot for *;

    /// @dev Namespaces of the two transient reservation counters (EIP-1153), see the contract docs.
    bytes32 private constant _RESERVED_BY_POLICY = keccak256("registerwerk.EwpgPaymaster.policyReserved");
    bytes32 private constant _RESERVED_BY_ORG = keccak256("registerwerk.EwpgPaymaster.reservedByOrg");

    /// @notice Operator safety valve and EntryPoint stake management — only for wallets of {operatorOrg}.
    bytes32 public constant CONFIGURE = keccak256("paymaster.configure");
    /// @notice Registering a sponsorship policy. Held (via any org) by whoever the operator lets
    ///         fund gas: the operator org itself, or issuers.
    bytes32 public constant REGISTER_POLICY = keccak256("paymaster.register-policy");
    uint256 public constant TOPIC_KYC = 1;

    /// @notice Domain tag of the voucher digest — keeps a voucher signature from ever being a
    ///         valid signature over any other Registerwerk-signed payload (e.g. a KYC claim).
    bytes32 public constant VOUCHER_TYPEHASH = keccak256("EwpgPaymasterVoucher(v1)");

    /// @notice Byte length of `paymasterData` (after the 52-byte address+gas-limit prefix).
    uint256 public constant PAYMASTER_DATA_LENGTH = 32 + 6 + 6 + 16 + 65;

    /// @notice EntryPoint gas spent around the postOp call itself, on top of `postOpGasLimit`
    ///         (which already bounds postOp's own gas plus its 10% unused-gas penalty).
    uint256 public constant POST_OP_OVERHEAD_GAS = 10_000;

    /// @notice Minimum `paymasterPostOpGasLimit` — below it postOp could run out of gas.
    /// @dev The worst case is the first sponsorship of an org under a policy, when `spentByOrg` is a
    ///      new storage slot: ~110k gas under EIP-8037/8038 (it was ~22k, hence the former 50,000)
    ///      plus ~25k for the two existing slots (`policyBalance`, `totalBooked`) and the event.
    uint256 public constant MIN_POST_OP_GAS_LIMIT = 150_000;

    /// @notice The ERC-4337 EntryPoint this paymaster is registered with.
    IEntryPoint public immutable entryPoint;

    /// @notice The org (ONCHAINID address) operating this instance — the only org whose
    ///         `paymaster.configure` holders have admin reach (safety valve, stake).
    address public immutable operatorOrg;

    /// @notice policyId => the address that registered (and owns the budget of) the policy.
    mapping(bytes32 => address) public funder;
    /// @notice policyId => the address whose signature authorises a sponsorship voucher.
    mapping(bytes32 => address) public policySigner;
    /// @notice policyId => whether vouchers for this policy are currently honoured.
    mapping(bytes32 => bool) public policyActive;
    /// @notice policyId => maximum cumulative wei any single org may consume. Always > 0.
    mapping(bytes32 => uint256) public orgBudgetCap;

    /// @notice policyId => unreserved sponsorship budget, in wei.
    mapping(bytes32 => uint256) public policyBalance;
    /// @notice policyId => sender org => cumulative wei booked against this policy.
    mapping(bytes32 => mapping(address => uint256)) public spentByOrg;

    /// @notice Σ policyBalance + Σ policyReserved — what the books say the deposit must cover.
    uint256 public totalBooked;

    /// @notice policyId => wei reserved by validated-but-not-yet-settled UserOperations of the
    ///         *current transaction* (transient: always 0 between transactions).
    function policyReserved(bytes32 policyId) public view returns (uint256) {
        return _RESERVED_BY_POLICY.deriveMapping(policyId).asUint256().tload();
    }

    /// @notice policyId => sender org => wei currently reserved (transient, see {policyReserved}).
    function reservedByOrg(bytes32 policyId, address org) public view returns (uint256) {
        return _RESERVED_BY_ORG.deriveMapping(policyId).deriveMapping(org).asUint256().tload();
    }

    /// @notice Who supplied the current EntryPoint stake; {withdrawStake} always pays them.
    address public stakeFunder;

    event PolicyRegistered(bytes32 indexed policyId, address indexed funder, address signer, uint256 orgBudgetCap);
    event PolicySignerSet(bytes32 indexed policyId, address signer);
    event OrgBudgetCapSet(bytes32 indexed policyId, uint256 cap);
    event PolicyActiveSet(bytes32 indexed policyId, bool active);
    event PolicyFunded(bytes32 indexed policyId, address indexed funder, uint256 amount);
    event PolicyWithdrawn(bytes32 indexed policyId, address indexed funder, uint256 amount);
    event GasSponsored(bytes32 indexed policyId, address indexed wallet, address indexed org, uint256 charged);
    event StakeAdded(address indexed stakeFunder, uint256 amount, uint32 unstakeDelaySec);
    event StakeWithdrawn(address indexed stakeFunder);

    error NotEntryPoint();
    error NotVerifiedMember(address wallet);
    error InvalidPaymasterData();
    error PolicyNotRegistered(bytes32 policyId);
    error PolicyAlreadyRegistered(bytes32 policyId);
    error PolicyInactive(bytes32 policyId);
    error NotPolicyFunder(bytes32 policyId, address caller);
    error NotPolicyAdmin(bytes32 policyId, address caller);
    error NotStakeAdmin(address caller);
    error ZeroSigner();
    error ZeroOrgCap();
    error ZeroAmount();
    error GasPriceAboveCap(uint256 maxFeePerGas, uint256 cap);
    error PostOpGasLimitTooLow(uint256 postOpGasLimit);
    error PolicyBudgetExceeded(bytes32 policyId);
    error OrgBudgetExceeded(bytes32 policyId, address org);
    error ZeroAddressEntryPoint();

    modifier onlyEntryPoint() {
        if (msg.sender != address(entryPoint)) revert NotEntryPoint();
        _;
    }

    modifier onlyFunder(bytes32 policyId) {
        if (msg.sender != funder[policyId]) revert NotPolicyFunder(policyId, msg.sender);
        _;
    }

    /// @dev The policy's funder, or a `paymaster.configure` holder of {operatorOrg} (operator
    ///      safety valve).
    modifier onlyPolicyAdmin(bytes32 policyId) {
        address f = funder[policyId];
        if (f == address(0)) revert PolicyNotRegistered(policyId);
        if (msg.sender != f && !_isOperatorConfigurer(msg.sender)) {
            revert NotPolicyAdmin(policyId, msg.sender);
        }
        _;
    }

    /// @param operatorOrg_ The org operating this instance (see {operatorOrg}).
    constructor(IPermissionOracle oracle_, IEntryPoint entryPoint_, address operatorOrg_)
        RegisterwerkGated(oracle_)
    {
        if (address(entryPoint_) == address(0)) revert ZeroAddressEntryPoint();
        _requireOrg(operatorOrg_);
        entryPoint = entryPoint_;
        operatorOrg = operatorOrg_;
    }

    // ── Policy lifecycle ──────────────────────────────────────────────────────

    /// @notice Registers `policyId` with the caller as its funder, the voucher `signer` and a
    ///         per-org cap, optionally funding it with `msg.value` in the same call. Requires
    ///         `paymaster.register-policy` via the caller's org — an id cannot be taken by a
    ///         wallet the operator has not approved as a sponsor.
    function registerPolicy(bytes32 policyId, address signer, uint256 orgCap)
        external
        payable
        requiresPermission(REGISTER_POLICY)
    {
        if (funder[policyId] != address(0)) revert PolicyAlreadyRegistered(policyId);
        if (signer == address(0)) revert ZeroSigner();
        if (orgCap == 0) revert ZeroOrgCap();
        funder[policyId] = msg.sender;
        policySigner[policyId] = signer;
        orgBudgetCap[policyId] = orgCap;
        policyActive[policyId] = true;
        emit PolicyRegistered(policyId, msg.sender, signer, orgCap);
        emit PolicyActiveSet(policyId, true);
        if (msg.value > 0) _fund(policyId, msg.value);
    }

    /// @notice Tops up `policyId` and forwards the deposit to the EntryPoint. Funder only, so
    ///         the whole unreserved balance of a policy always belongs to one withdrawable owner.
    function fundSponsorship(bytes32 policyId) external payable onlyFunder(policyId) {
        if (msg.value == 0) revert ZeroAmount();
        _fund(policyId, msg.value);
    }

    /// @notice Rotates the voucher signer. Funder only — a signer can spend the policy.
    function setPolicySigner(bytes32 policyId, address signer) external onlyFunder(policyId) {
        if (signer == address(0)) revert ZeroSigner();
        policySigner[policyId] = signer;
        emit PolicySignerSet(policyId, signer);
    }

    /// @notice Sets the maximum cumulative wei a single org may consume against `policyId`.
    function setOrgBudgetCap(bytes32 policyId, uint256 cap) external onlyPolicyAdmin(policyId) {
        if (cap == 0) revert ZeroOrgCap();
        orgBudgetCap[policyId] = cap;
        emit OrgBudgetCapSet(policyId, cap);
    }

    /// @notice On-chain kill switch: an inactive policy fails validation for every voucher.
    function setPolicyActive(bytes32 policyId, bool active) external onlyPolicyAdmin(policyId) {
        policyActive[policyId] = active;
        emit PolicyActiveSet(policyId, active);
    }

    /// @notice Returns `amount` of `policyId`'s *unreserved* budget from the EntryPoint deposit
    ///         to the policy's recorded funder. A configure holder may trigger it but the
    ///         recipient is never selectable.
    function withdrawPolicy(bytes32 policyId, uint256 amount) external onlyPolicyAdmin(policyId) {
        if (amount == 0) revert ZeroAmount();
        if (amount > policyBalance[policyId]) revert PolicyBudgetExceeded(policyId);
        policyBalance[policyId] -= amount;
        totalBooked -= amount;
        address payable to = payable(funder[policyId]);
        emit PolicyWithdrawn(policyId, to, amount);
        entryPoint.withdrawTo(to, amount);
    }

    function _fund(bytes32 policyId, uint256 amount) private {
        policyBalance[policyId] += amount;
        totalBooked += amount;
        entryPoint.depositTo{value: amount}(address(this));
        emit PolicyFunded(policyId, msg.sender, amount);
    }

    // ── EntryPoint stake (ERC-7562) ───────────────────────────────────────────

    /// @notice Stakes `msg.value` in the EntryPoint. Operator-org `paymaster.configure` holders
    ///         only. The first staker becomes `stakeFunder`; top-ups must come from the same
    ///         address so the stake has one owner.
    function addStake(uint32 unstakeDelaySec) external payable requiresOrgPermission(operatorOrg, CONFIGURE) {
        if (msg.value == 0) revert ZeroAmount();
        if (stakeFunder == address(0)) {
            stakeFunder = msg.sender;
        } else if (stakeFunder != msg.sender) {
            revert NotStakeAdmin(msg.sender);
        }
        emit StakeAdded(msg.sender, msg.value, unstakeDelaySec);
        entryPoint.addStake{value: msg.value}(unstakeDelaySec);
    }

    /// @notice Starts the unstake delay. Only `stakeFunder` may do this: de-staking makes
    ///         public bundlers drop the paymaster, so an unbound `paymaster.configure` holder
    ///         (any org granted it) must not be able to switch sponsorship off for everyone.
    function unlockStake() external {
        if (msg.sender != stakeFunder) revert NotStakeAdmin(msg.sender);
        entryPoint.unlockStake();
    }

    /// @notice After the unstake delay, returns the whole stake to `stakeFunder`.
    function withdrawStake() external {
        _requireStakeAdmin();
        address payable to = payable(stakeFunder);
        stakeFunder = address(0);
        emit StakeWithdrawn(to);
        entryPoint.withdrawStake(to);
    }

    function _requireStakeAdmin() private view {
        if (msg.sender != stakeFunder && !_isOperatorConfigurer(msg.sender)) {
            revert NotStakeAdmin(msg.sender);
        }
    }

    /// @dev The caller's wallet is bound to {operatorOrg} **and** holds `paymaster.configure` via it.
    function _isOperatorConfigurer(address wallet) private view returns (bool) {
        return oracle.orgOf(wallet) == operatorOrg && oracle.hasPermission(wallet, CONFIGURE);
    }

    /// @notice EntryPoint deposit minus what the books owe to policies. Must never go
    ///         negative; SRE alerts on it (`paymaster_deposit_minus_booked_wei`).
    function depositSurplus() external view returns (int256) {
        return int256(entryPoint.balanceOf(address(this))) - int256(totalBooked);
    }

    // ── Vouchers ──────────────────────────────────────────────────────────────

    /// @notice The digest a policy signer signs (EIP-191 `personal_sign` over these 32 bytes)
    ///         to sponsor `userOp`. Covers every UserOperation field except the voucher
    ///         signature itself, plus chain, paymaster, policy, validity window and fee cap.
    function getHash(
        PackedUserOperation calldata userOp,
        bytes32 policyId,
        uint48 validUntil,
        uint48 validAfter,
        uint128 maxFeePerGasCap
    ) public view returns (bytes32) {
        bytes32 opHash = keccak256(
            abi.encode(
                userOp.sender,
                userOp.nonce,
                keccak256(userOp.initCode),
                keccak256(userOp.callData),
                userOp.accountGasLimits,
                userOp.paymasterVerificationGasLimit(),
                userOp.paymasterPostOpGasLimit(),
                userOp.preVerificationGas,
                userOp.gasFees
            )
        );
        return keccak256(
            abi.encode(
                VOUCHER_TYPEHASH,
                opHash,
                block.chainid,
                address(this),
                policyId,
                validUntil,
                validAfter,
                maxFeePerGasCap
            )
        );
    }

    /// @notice Splits `paymasterData` into its voucher fields.
    function parsePaymasterData(bytes calldata data)
        public
        pure
        returns (bytes32 policyId, uint48 validUntil, uint48 validAfter, uint128 maxFeePerGasCap, bytes calldata sig)
    {
        if (data.length != PAYMASTER_DATA_LENGTH) revert InvalidPaymasterData();
        policyId = bytes32(data[0:32]);
        validUntil = uint48(bytes6(data[32:38]));
        validAfter = uint48(bytes6(data[38:44]));
        maxFeePerGasCap = uint128(bytes16(data[44:60]));
        sig = data[60:];
    }

    // ── ERC-4337 IPaymaster ───────────────────────────────────────────────────

    /// @inheritdoc IPaymaster
    function validatePaymasterUserOp(PackedUserOperation calldata userOp, bytes32, uint256 maxCost)
        external
        onlyEntryPoint
        returns (bytes memory context, uint256 validationData)
    {
        bytes32 policyId;
        (policyId, validationData) = _checkVoucher(userOp);

        // Defence in depth: the voucher issuer checks this too.
        address sender = userOp.sender;
        if (!oracle.isActiveMember(sender) || !oracle.hasClaimTopic(sender, TOPIC_KYC)) {
            revert NotVerifiedMember(sender);
        }
        address org = oracle.orgOf(sender);

        // Reserve the worst case now, so later ops in the same bundle see the reduced budget.
        if (maxCost > policyBalance[policyId]) revert PolicyBudgetExceeded(policyId);
        TransientSlot.Uint256Slot orgReserved = _RESERVED_BY_ORG.deriveMapping(policyId).deriveMapping(org).asUint256();
        uint256 orgReservedNow = orgReserved.tload();
        if (spentByOrg[policyId][org] + orgReservedNow + maxCost > orgBudgetCap[policyId]) {
            revert OrgBudgetExceeded(policyId, org);
        }
        policyBalance[policyId] -= maxCost;
        TransientSlot.Uint256Slot policyReservedSlot = _RESERVED_BY_POLICY.deriveMapping(policyId).asUint256();
        policyReservedSlot.tstore(policyReservedSlot.tload() + maxCost);
        orgReserved.tstore(orgReservedNow + maxCost);

        context = abi.encode(policyId, org, sender, maxCost, userOp.paymasterPostOpGasLimit());
    }

    /// @dev Decodes the voucher, enforces the policy flag, fee cap and postOp gas floor
    ///      (reverting), and checks the signature (returning `SIG_VALIDATION_FAILED` packed
    ///      with the validity window on mismatch, never reverting).
    function _checkVoucher(PackedUserOperation calldata userOp)
        private
        view
        returns (bytes32 policyId, uint256 validationData)
    {
        // Sliced directly (not ERC4337Utils.paymasterData) — EntryPoint v0.8 has no
        // paymaster-signature suffix, and our 65 signature bytes must never be mistaken for one.
        if (userOp.paymasterAndData.length < 52) revert InvalidPaymasterData();
        (bytes32 p, uint48 validUntil, uint48 validAfter, uint128 cap, bytes calldata sig) =
            parsePaymasterData(userOp.paymasterAndData[52:]);

        if (!policyActive[p]) revert PolicyInactive(p);
        if (userOp.maxFeePerGas() > cap) revert GasPriceAboveCap(userOp.maxFeePerGas(), cap);
        if (userOp.paymasterPostOpGasLimit() < MIN_POST_OP_GAS_LIMIT) {
            revert PostOpGasLimitTooLow(userOp.paymasterPostOpGasLimit());
        }

        bytes32 digest = MessageHashUtils.toEthSignedMessageHash(getHash(userOp, p, validUntil, validAfter, cap));
        (address recovered, ECDSA.RecoverError err,) = ECDSA.tryRecover(digest, sig);
        bool sigOk = err == ECDSA.RecoverError.NoError && recovered == policySigner[p];
        return (p, ERC4337Utils.packValidationData(sigOk, validAfter, validUntil));
    }

    /// @inheritdoc IPaymaster
    /// @dev Never reverts: the booked charge is capped at the reservation it releases.
    function postOp(PostOpMode, bytes calldata context, uint256 actualGasCost, uint256 actualUserOpFeePerGas)
        external
        onlyEntryPoint
    {
        (bytes32 policyId, address org, address wallet, uint256 maxCost, uint256 postOpGasLimit) =
            abi.decode(context, (bytes32, address, address, uint256, uint256));

        uint256 charge = actualGasCost + (postOpGasLimit + POST_OP_OVERHEAD_GAS) * actualUserOpFeePerGas;
        if (charge > maxCost) charge = maxCost;

        // Safe: validation added exactly `maxCost` to both reservations, and charge <= maxCost.
        TransientSlot.Uint256Slot policyReservedSlot = _RESERVED_BY_POLICY.deriveMapping(policyId).asUint256();
        TransientSlot.Uint256Slot orgReserved = _RESERVED_BY_ORG.deriveMapping(policyId).deriveMapping(org).asUint256();
        unchecked {
            policyReservedSlot.tstore(policyReservedSlot.tload() - maxCost);
            orgReserved.tstore(orgReserved.tload() - maxCost);
            policyBalance[policyId] += maxCost - charge;
            totalBooked -= charge;
        }
        spentByOrg[policyId][org] += charge;
        emit GasSponsored(policyId, wallet, org, charge);
    }
}
