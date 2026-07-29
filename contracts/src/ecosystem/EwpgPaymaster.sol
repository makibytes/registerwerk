// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {IEntryPoint, IPaymaster, PackedUserOperation} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import {ERC4337Utils} from "@openzeppelin/contracts/account/utils/draft-ERC4337Utils.sol";
import "./RegisterwerkGated.sol";
import "./interfaces/IPermissionOracle.sol";

/// @title EwpgPaymaster
/// @notice Reference marketplace dApp: an ERC-4337 paymaster that sponsors gas for verified
///         Registerwerk customers, funded per policy by the operator or by an issuer at
///         token-issuance time (see `docs/platform/account-abstraction.md`).
///
/// @dev Deployed against EntryPoint v0.8 (`ERC4337Utils.ENTRYPOINT_V08`) — chosen for native
///      EIP-7702 support, matching the smart-account on-ramp design. Because a 7702-delegated
///      EOA keeps its original address, `userOp.sender` *is* the customer's existing
///      Registerwerk member-wallet address — so compliance gating here reads directly off
///      `PermissionOracle` for that address (`isActiveMember` + a KYC claim), exactly the
///      same fail-closed pattern every other ecosystem contract uses, just keyed on
///      `userOp.sender` instead of `msg.sender` (the caller of {validatePaymasterUserOp} is
///      always the EntryPoint itself, never the sponsored wallet).
///
///      Sponsorship is scoped by an opaque `policyId` (e.g. `keccak256(assetDeploymentId)` for
///      an issuer's own instrument, or a fixed operator-wide default) that the sponsoring
///      wallet or dApp encodes into `paymasterData` — the last 32 bytes of `paymasterAndData`.
///      Funding a policy both earmarks an internal budget *and* forwards the deposit to the
///      EntryPoint via {IEntryPoint-depositTo}, since ERC-4337 requires the paymaster
///      contract itself to hold sufficient EntryPoint stake to cover sponsored gas.
///
///      A per-wallet spend cap per policy (`walletBudgetCap`) bounds how much of a *shared*
///      policy budget any single wallet can consume — the aggregate `policyBalance` already
///      caps worst-case loss to what the sponsor deposited, but without a per-wallet cap one
///      compromised or malicious wallet could exhaust an entire shared pool meant for many
///      sponsored customers.
///
///      Reference-implementation scope: this contract does not restrict *which* target
///      contract a sponsored UserOperation calls — parsing an arbitrary smart-account's
///      `callData` to recover the ultimate target is account-implementation-specific and not
///      reliably done generically in a paymaster. The compliance gate is therefore on *who*
///      (`userOp.sender`'s KYC/membership status), not *what* they call; scoping to a
///      target allowlist as well is a natural extension once a specific account/callData
///      convention is standardized on.
contract EwpgPaymaster is RegisterwerkGated, IPaymaster {
    bytes32 public constant CONFIGURE = keccak256("paymaster.configure");
    uint256 public constant TOPIC_KYC = 1;

    /// @notice The ERC-4337 EntryPoint this paymaster is registered with.
    IEntryPoint public immutable entryPoint;

    /// @notice policyId => remaining sponsorship budget, in wei.
    mapping(bytes32 => uint256) public policyBalance;

    /// @notice policyId => per-wallet spend cap, in wei. 0 = no cap.
    mapping(bytes32 => uint256) public walletBudgetCap;

    /// @notice policyId => sponsored wallet => cumulative wei spent against this policy.
    mapping(bytes32 => mapping(address => uint256)) public spentByWallet;

    event PolicyFunded(bytes32 indexed policyId, address indexed funder, uint256 amount);
    event WalletBudgetCapSet(bytes32 indexed policyId, uint256 cap);
    event GasSponsored(bytes32 indexed policyId, address indexed wallet, uint256 actualGasCost);

    error NotEntryPoint();
    error NotVerifiedMember(address wallet);
    error MissingPolicyId();
    error PolicyBudgetExceeded(bytes32 policyId);
    error WalletBudgetExceeded(bytes32 policyId, address wallet);
    error ZeroAddressEntryPoint();

    modifier onlyEntryPoint() {
        if (msg.sender != address(entryPoint)) revert NotEntryPoint();
        _;
    }

    constructor(IPermissionOracle oracle_, IEntryPoint entryPoint_) RegisterwerkGated(oracle_) {
        if (address(entryPoint_) == address(0)) revert ZeroAddressEntryPoint();
        entryPoint = entryPoint_;
    }

    // ── Sponsor funding ───────────────────────────────────────────────────────

    /// @notice Deposits `msg.value` into `policyId`'s sponsorship budget and forwards it to
    ///         the EntryPoint so this paymaster can actually cover sponsored gas. Callable by
    ///         anyone — the operator, an issuer's treasury, or any other willing sponsor.
    function fundSponsorship(bytes32 policyId) external payable {
        policyBalance[policyId] += msg.value;
        entryPoint.depositTo{value: msg.value}(address(this));
        emit PolicyFunded(policyId, msg.sender, msg.value);
    }

    /// @notice Sets the maximum cumulative wei a single wallet may consume against a shared
    ///         policy budget (0 = uncapped).
    function setWalletBudgetCap(bytes32 policyId, uint256 cap) external requiresPermission(CONFIGURE) {
        walletBudgetCap[policyId] = cap;
        emit WalletBudgetCapSet(policyId, cap);
    }

    // ── ERC-4337 IPaymaster ───────────────────────────────────────────────────

    /// @inheritdoc IPaymaster
    function validatePaymasterUserOp(PackedUserOperation calldata userOp, bytes32, uint256 maxCost)
        external
        view
        onlyEntryPoint
        returns (bytes memory context, uint256 validationData)
    {
        if (!oracle.isActiveMember(userOp.sender) || !oracle.hasClaimTopic(userOp.sender, TOPIC_KYC)) {
            revert NotVerifiedMember(userOp.sender);
        }

        bytes calldata data = ERC4337Utils.paymasterData(userOp);
        if (data.length < 32) revert MissingPolicyId();
        bytes32 policyId = bytes32(data[0:32]);

        if (maxCost > policyBalance[policyId]) revert PolicyBudgetExceeded(policyId);
        uint256 cap = walletBudgetCap[policyId];
        if (cap > 0 && spentByWallet[policyId][userOp.sender] + maxCost > cap) {
            revert WalletBudgetExceeded(policyId, userOp.sender);
        }

        context = abi.encode(policyId, userOp.sender);
        validationData = ERC4337Utils.packValidationData(true, 0, 0);
    }

    /// @inheritdoc IPaymaster
    function postOp(PostOpMode, bytes calldata context, uint256 actualGasCost, uint256) external onlyEntryPoint {
        (bytes32 policyId, address wallet) = abi.decode(context, (bytes32, address));
        policyBalance[policyId] -= actualGasCost;
        spentByWallet[policyId][wallet] += actualGasCost;
        emit GasSponsored(policyId, wallet, actualGasCost);
    }
}
