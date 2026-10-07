package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequest;
import de.makibytes.registerwerk.deployment.api.VaultRequestRepository;
import de.makibytes.registerwerk.deployment.api.VaultRequestStatus;
import de.makibytes.registerwerk.deployment.api.VaultRequestType;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.VaultDealingState;
import de.makibytes.registerwerk.blockchain.api.VaultRequestView;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.blockchain.events.TokenAdminActionEvent;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Registry-operator controls for EwpgERC7540 (async vault) request fulfillment.
 *
 * <p>Operators use this service to review and fulfill pending deposit/redeem requests
 * after striking a NAV (via {@link Erc4626AdminService#strikeNav}).
 *
 * <p>Fulfillment uses the NAV effective on-chain at execution time — operators must ensure a
 * current NAV strike exists before fulfilling requests. The executed NAV/amounts are recorded by
 * {@code VaultConfirmationListener} from the fulfilment event, never taken from the caller.
 *
 * <p>Compliance holds are freeze-in-place (review finding T1-05): before submitting, a fulfil is
 * refused when the request <em>owner</em> — the party that receives shares or assets — is frozen
 * on the vault or under a §16 eWpG Sperrvermerk, and a cancel is refused when its refund
 * recipient (the deposit payer, or the owner for a redeem) is. The controller is deliberately not
 * checked: it never receives value, and blocking on it would let anyone stall their own request
 * by naming a frozen controller. Only {@link #forceCancelRequest} (step-up + 4-eyes, legal basis,
 * destination named per case) moves the escrow of a held request. Every submission is also
 * pre-flighted with an {@code eth_call} so a contract revert surfaces as a meaningful error
 * ({@link VaultRevertReasons}) instead of a failed transaction.
 */
@Service
@Transactional
public class Erc7540AdminService implements de.makibytes.registerwerk.blockchain.api.Erc7540AdminPort {

    private static final Logger log = LoggerFactory.getLogger(Erc7540AdminService.class);
    private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final AssetDeploymentRepository deploymentRepository;
    private final VaultRequestRepository vaultRequestRepository;
    private final de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway durableTransactions;
    private final BlockchainTransactionService txService;
    private final ApplicationEventPublisher eventPublisher;
    private final EvmContractService evmContractService;
    private final HolderBlockGate holderBlockGate;
    private final VaultDealingReader dealingReader;
    private final VaultDealingSettings dealingSettings;
    private final AssetLookupPort assetLookup;

    public Erc7540AdminService(
            AssetDeploymentRepository deploymentRepository,
            VaultRequestRepository vaultRequestRepository,
            de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway durableTransactions,
            BlockchainTransactionService txService,
            ApplicationEventPublisher eventPublisher,
            EvmContractService evmContractService,
            HolderBlockGate holderBlockGate,
            VaultDealingReader dealingReader,
            VaultDealingSettings dealingSettings,
            AssetLookupPort assetLookup) {
        this.dealingReader = dealingReader;
        this.dealingSettings = dealingSettings;
        this.assetLookup = assetLookup;
        this.deploymentRepository = deploymentRepository;
        this.vaultRequestRepository = vaultRequestRepository;
        this.durableTransactions = durableTransactions;
        this.txService = txService;
        this.eventPublisher = eventPublisher;
        this.evmContractService = evmContractService;
        this.holderBlockGate = holderBlockGate;
    }

    // ── Fulfill deposit request ───────────────────────────────────────────────

    public UUID fulfillDepositRequest(UUID deploymentId, BigInteger onChainRequestId,
                                      UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, VaultRequestType.DEPOSIT);
        return fulfill(dep, request, actorId, actorRole);
    }

    // ── Fulfill redeem request ────────────────────────────────────────────────

    public UUID fulfillRedeemRequest(UUID deploymentId, BigInteger onChainRequestId,
                                     UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, VaultRequestType.REDEEM);
        return fulfill(dep, request, actorId, actorRole);
    }

    @Override
    public UUID fulfillRequest(UUID deploymentId, BigInteger onChainRequestId,
                               UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, null);
        return fulfill(dep, request, actorId, actorRole);
    }

    /**
     * Submits the fulfil tx and records it on the request row, but deliberately does <b>not</b>
     * flip {@code requestStatus} to {@code FULFILLED} yet — {@link EvmContractService#submit}
     * returns before any receipt exists, so at this point fulfilment is only submitted, not
     * confirmed. {@code VaultConfirmationListener} applies the status transition once the tx
     * reaches FINALIZED.
     */
    private UUID fulfill(AssetDeployment dep, VaultRequest request, UUID actorId, String actorRole) {
        String functionName = request.getRequestType() == VaultRequestType.DEPOSIT
                ? "fulfillDepositRequest" : "fulfillRedeemRequest";
        log.info("Fulfilling {} request={} on deployment={} at the NAV currently struck on-chain",
                request.getRequestType(), request.getRequestId(), dep.getId());
        // T1-05: the owner receives the shares (deposit) / assets (redeem) — never the controller.
        requireNoComplianceHold(dep, request.getOwnerAddr(), "fulfilment");
        Function fn = new Function(functionName,
                Collections.singletonList(new Uint256(request.getRequestId())),
                Collections.emptyList());
        preflight(dep, fn, "fulfilment");
        SubmittedTx tx = submitEvm(dep, fn, functionName,
                Map.of("requestId", request.getRequestId().toString(),
                        "owner", request.getOwnerAddr()), actorId, actorRole);
        request.setFulfilledTx(tx.txHash());
        vaultRequestRepository.save(request);
        return tx.txId();
    }

    // ── Cancel request ────────────────────────────────────────────────────────

    public UUID cancelDepositRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole) {
        log.info("Cancelling deposit request={} on deployment={}", onChainRequestId, deploymentId);
        return cancelExpectedRequest(
                deploymentId, onChainRequestId, VaultRequestType.DEPOSIT, actorId, actorRole);
    }

    public UUID cancelRedeemRequest(UUID deploymentId, BigInteger onChainRequestId, UUID actorId, String actorRole) {
        log.info("Cancelling redeem request={} on deployment={}", onChainRequestId, deploymentId);
        return cancelExpectedRequest(
                deploymentId, onChainRequestId, VaultRequestType.REDEEM, actorId, actorRole);
    }

    @Override
    public UUID cancelRequest(UUID deploymentId, BigInteger onChainRequestId,
                              UUID actorId, String actorRole) {
        return cancelExpectedRequest(deploymentId, onChainRequestId, null, actorId, actorRole);
    }

    /** Same "submitted, not yet confirmed" reasoning as {@link #fulfill} — {@code requestStatus}
     *  stays {@code PENDING} until {@code VaultConfirmationListener} confirms {@code cancelledTx}. */
    private UUID cancelExpectedRequest(UUID deploymentId, BigInteger onChainRequestId,
                                       VaultRequestType expectedType, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, expectedType);
        String fnName = request.getRequestType() == VaultRequestType.DEPOSIT
                ? "cancelDepositRequest" : "cancelRedeemRequest";
        // Freeze-in-place: a frozen refund recipient's escrow stays in the vault.
        requireNoComplianceHold(dep, refundRecipient(request), "cancellation");
        Function fn = new Function(fnName,
                Collections.singletonList(new Uint256(onChainRequestId)),
                Collections.emptyList());
        preflight(dep, fn, "cancellation");
        SubmittedTx tx = submitEvm(dep, fn, fnName, Map.of("requestId", onChainRequestId.toString()), actorId, actorRole);
        request.setCancelledTx(tx.txHash());
        vaultRequestRepository.save(request);
        return tx.txId();
    }

    // ── Force-cancel (compliance hold release on a legal basis) ───────────────

    /**
     * Registry force-cancel ({@code forceCancelDepositRequest}/{@code forceCancelRedeemRequest}):
     * the request's escrow — underlying for a deposit, escrowed shares for a redeem — goes to
     * {@code toAddress}, a destination named per case under {@code legalBasis}. Policy-neutral by
     * design; deliberately not gated on the owner's freeze (releasing a held request is its
     * purpose). The controller layer requires step-up + a second approver.
     */
    @Override
    public UUID forceCancelRequest(UUID deploymentId, BigInteger onChainRequestId, String toAddress,
                                   String legalBasis, UUID actorId, String actorRole) {
        AssetDeployment dep = requireDeployment(deploymentId);
        VaultRequest request = requirePendingRequest(dep, onChainRequestId, null);
        if (legalBasis == null || legalBasis.isBlank()) {
            throw new IllegalArgumentException("A legal basis is required to force-cancel a vault request");
        }
        if (toAddress == null || !EVM_ADDRESS.matcher(toAddress).matches()) {
            throw new IllegalArgumentException("Force-cancel destination must be an EVM address");
        }
        String to = toAddress.toLowerCase(Locale.ROOT);
        if (ZERO_ADDRESS.equals(to) || to.equalsIgnoreCase(dep.getContractAddress())) {
            throw new IllegalArgumentException("Force-cancel destination must be a non-zero address other than "
                    + "the vault itself.");
        }
        if (holderBlockGate.isBlocked(null, to)) {
            throw new ComplianceGateException("Force-cancel destination " + to + " is subject to an active §16 eWpG "
                    + "Sperrvermerk (legal block) — choose a different destination.");
        }
        String fnName = request.getRequestType() == VaultRequestType.DEPOSIT
                ? "forceCancelDepositRequest" : "forceCancelRedeemRequest";
        log.info("Force-cancelling {} request={} on deployment={} to={}",
                request.getRequestType(), onChainRequestId, dep.getId(), to);
        Function fn = new Function(fnName,
                List.of(new Uint256(onChainRequestId), new Address(to), new Utf8String(legalBasis)),
                Collections.emptyList());
        preflight(dep, fn, "force-cancel");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", onChainRequestId.toString());
        params.put("requestType", request.getRequestType().name());
        params.put("owner", request.getOwnerAddr());
        params.put("to", to);
        params.put("legalBasis", legalBasis);
        SubmittedTx tx = submitEvm(dep, fn, fnName, params, actorId, actorRole);
        request.setCancelledTx(tx.txHash());
        request.setForcedToAddr(to);
        request.setLegalBasis(legalBasis);
        vaultRequestRepository.save(request);
        return tx.txId();
    }

    // ── Forward pricing: dealing cut-off (T1-07) ─────────────────────────────

    /**
     * Registry {@code setDealingCutoff}: from the moment it confirms, a request placed on the vault settles
     * only at a NAV struck at or after its dealing point (the first cut-off boundary after the request).
     * Requests already placed keep the dealing point they were given. Audited as
     * {@code TOKEN_ADMIN_SET_DEALING_CUTOFF}; the controller layer adds step-up and a second approver.
     */
    @Override
    public UUID setDealingCutoff(UUID deploymentId, int cutoffSecondsOfDay, long periodSeconds,
                                 UUID actorId, String actorRole) {
        VaultDealingSettings.requireValid(cutoffSecondsOfDay, periodSeconds);
        AssetDeployment dep = requireDeployment(deploymentId);
        requireErc7540(dep);
        log.info("Setting dealing cut-off {} UTC / period {}s on deployment={}",
                VaultDealingSettings.formatCutoff(cutoffSecondsOfDay), periodSeconds, dep.getId());
        return submitDealingCutoff(dep, cutoffSecondsOfDay, periodSeconds, actorId, actorRole);
    }

    /**
     * Runs after the deployment confirmation committed (own transaction, hence {@code REQUIRES_NEW}): sends
     * the configured default cut-off through the durable outbox as the registry signer. Never throws — a vault
     * left without a cut-off is refused for subscriptions in production mode and listed by
     * {@link VaultDealingReadinessCheck}, and an operator can set it with {@link #setDealingCutoff}.
     */
    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void configureDealingCutoffAfterDeployment(UUID deploymentId) {
        try {
            if (!dealingSettings.autoConfigure()) {
                log.info("Automatic dealing cut-off configuration is switched off; vault deployment={} stays "
                        + "unconfigured until an operator sets it", deploymentId);
                return;
            }
            AssetDeployment dep = requireDeployment(deploymentId);
            if (!isErc7540(dep)) {
                return;
            }
            Optional<VaultDealingState> state = dealingReader.readState(dep);
            if (state.isPresent() && state.get().configured()) {
                log.info("Vault deployment={} already has a dealing cut-off; leaving it as it is", deploymentId);
                return;
            }
            submitDealingCutoff(dep, dealingSettings.defaultCutoffSecondsOfDay(),
                    dealingSettings.defaultPeriodSeconds(), null, "SYSTEM");
            log.info("Default dealing cut-off {} UTC / period {}s sent for vault deployment={}",
                    VaultDealingSettings.formatCutoff(dealingSettings.defaultCutoffSecondsOfDay()),
                    dealingSettings.defaultPeriodSeconds(), deploymentId);
        } catch (RuntimeException e) {
            log.error("Could not send the default dealing cut-off for vault deployment={} — the vault stays "
                    + "WITHOUT forward pricing (production refuses subscriptions on it). Set it with POST "
                    + "/api/v1/deployments/{}/dealing-cutoff: {}", deploymentId, deploymentId, e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public VaultDealingState dealingState(UUID deploymentId) {
        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (!isErc7540(dep)) {
            return VaultDealingState.notApplicable();
        }
        if (!hasChainAddress(dep)) {
            return VaultDealingState.unavailable();
        }
        return dealingReader.readState(dep).orElseGet(VaultDealingState::unavailable);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireDealingCutoffConfigured(UUID assetId, String action) {
        if (!dealingSettings.production()) {
            return;
        }
        Optional<AssetLookupPort.AssetInfo> asset = assetLookup.findById(assetId);
        if (asset.isEmpty() || asset.get().tokenStandard() != TokenStandard.ERC7540) {
            return;
        }
        for (AssetDeployment dep : deploymentRepository.findByAssetId(assetId)) {
            if (!isConfirmedVault(dep)) {
                continue;
            }
            Optional<VaultDealingState> state = dealingReader.readState(dep);
            if (state.isEmpty()) {
                throw new InvalidStateTransitionException("Vault " + dep.getContractAddress() + ": could not read "
                        + "the on-chain dealing cut-off — " + action + " is refused (fail closed); retry once the "
                        + "chain node is reachable.");
            }
            if (!state.get().configured()) {
                throw new InvalidStateTransitionException("Vault " + dep.getContractAddress() + " has no dealing "
                        + "cut-off configured — " + action + " is refused in production mode: without forward "
                        + "pricing a request could settle at a NAV that is already known (late trading). Set the "
                        + "dealing cut-off first (operator: asset > vault requests > Dealing cut-off).");
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listVaultsWithoutDealingCutoff() {
        List<String> lines = new java.util.ArrayList<>();
        for (AssetLookupPort.AssetInfo asset : assetLookup.findAll()) {
            if (asset.tokenStandard() != TokenStandard.ERC7540) {
                continue;
            }
            for (AssetDeployment dep : deploymentRepository.findByAssetId(asset.id())) {
                if (!isConfirmedVault(dep)) {
                    continue;
                }
                Optional<VaultDealingState> state = dealingReader.readState(dep);
                String where = asset.name() + " (" + dep.getContractAddress() + ") on " + dep.getChain() + "/"
                        + dep.getNetwork();
                if (state.isEmpty()) {
                    lines.add(where + " — dealing cut-off unreadable");
                } else if (!state.get().configured()) {
                    lines.add(where + " — no dealing cut-off configured");
                }
            }
        }
        return lines;
    }

    private UUID submitDealingCutoff(AssetDeployment dep, int cutoffSecondsOfDay, long periodSeconds,
                                     UUID actorId, String actorRole) {
        Function fn = new Function("setDealingCutoff",
                List.of(new Uint256(BigInteger.valueOf(cutoffSecondsOfDay)),
                        new Uint256(BigInteger.valueOf(periodSeconds))),
                Collections.emptyList());
        preflight(dep, fn, "setting the dealing cut-off");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cutoffSecondsOfDay", Integer.toString(cutoffSecondsOfDay));
        params.put("cutoffUtc", VaultDealingSettings.formatCutoff(cutoffSecondsOfDay));
        params.put("periodSeconds", Long.toString(periodSeconds));
        return submitEvm(dep, fn, "setDealingCutoff", params, actorId, actorRole).txId();
    }

    private boolean isErc7540(AssetDeployment dep) {
        return assetLookup.findById(dep.getAssetId())
                .map(a -> a.tokenStandard() == TokenStandard.ERC7540).orElse(false);
    }

    private void requireErc7540(AssetDeployment dep) {
        TokenStandard standard = assetLookup.findById(dep.getAssetId())
                .map(AssetLookupPort.AssetInfo::tokenStandard).orElse(null);
        if (standard != TokenStandard.ERC7540) {
            throw new IllegalArgumentException("The dealing cut-off exists on ERC-7540 vaults only; this "
                    + "deployment is " + standard);
        }
    }

    private static boolean hasChainAddress(AssetDeployment dep) {
        return dep.getContractAddress() != null && !dep.getContractAddress().isBlank()
                && dep.getChainConfigId() != null;
    }

    private static boolean isConfirmedVault(AssetDeployment dep) {
        return dep.getDeploymentStatus() == AssetDeployment.DeploymentStatus.CONFIRMED && hasChainAddress(dep);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VaultRequest> listRequests(UUID assetId, VaultRequestStatus status) {
        return vaultRequestRepository.findByAssetIdAndRequestStatus(assetId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VaultRequestView> listRequestViews(UUID deploymentId, VaultRequestStatus status) {
        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        // Forward pricing (T1-07): one live read of the vault's cut-off + last strike for the whole list.
        VaultDealingState dealing = status == VaultRequestStatus.PENDING && hasChainAddress(dep)
                ? dealingReader.readState(dep).orElse(null) : null;
        return vaultRequestRepository.findByAssetIdAndRequestStatus(dep.getAssetId(), status).stream()
                // Request ids are per vault: show only this deployment's chain (legacy rows without one included).
                .filter(r -> r.getChainConfigId() == null || dep.getChainConfigId() == null
                        || r.getChainConfigId().equals(dep.getChainConfigId()))
                .map(r -> {
                    if (r.getRequestStatus() != VaultRequestStatus.PENDING || dep.getContractAddress() == null) {
                        return VaultRequestView.of(r, false, null);
                    }
                    String reason = holdReasonOrUnavailable(dep, r.getOwnerAddr());
                    String refundTo = refundRecipient(r);
                    if (reason == null && refundTo != null && !refundTo.equalsIgnoreCase(r.getOwnerAddr())) {
                        reason = holdReasonOrUnavailable(dep, refundTo);
                    }
                    VaultRequestView view = VaultRequestView.of(r, reason != null, reason);
                    if (dealing != null && dealing.configured()) {
                        Optional<BigInteger> point = dealingReader.dealingPointOf(dep, r.getRequestId());
                        if (point.isPresent()) {
                            // 0 = placed before the cut-off was configured: legacy settlement, no dealing day
                            Instant dealingPoint = point.get().signum() == 0 ? null
                                    : Instant.ofEpochSecond(point.get().longValueExact());
                            view = view.withDealing(dealingPoint, dealing.navStruckAt());
                        }
                    }
                    return view;
                })
                .toList();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Who gets the escrow back on a cancel: the payer of a deposit (the owner on vaults that
     *  predate payer recording), the owner for a redeem (escrowed shares return to it). */
    private static String refundRecipient(VaultRequest request) {
        return request.getRequestType() == VaultRequestType.DEPOSIT && request.getPayerAddr() != null
                ? request.getPayerAddr() : request.getOwnerAddr();
    }

    /** Fail closed: an unreadable freeze status refuses the action. */
    private void requireNoComplianceHold(AssetDeployment dep, String address, String action) {
        String reason = holdReason(dep, address);
        if (reason != null) {
            throw new ComplianceGateException(reason + " — " + action + " refused. The escrow stays in the vault "
                    + "until the hold is lifted or the registry force-cancels the request on a legal basis.");
        }
    }

    /** @return why {@code address} is held, or null when it is clear */
    private String holdReason(AssetDeployment dep, String address) {
        if (holderBlockGate.isBlocked(null, address)) {
            return "Wallet " + address + " is subject to an active §16 eWpG Sperrvermerk (legal block)";
        }
        Boolean frozen;
        try {
            @SuppressWarnings("rawtypes")
            List<Type> out = evmContractService.call(evmContractService.evmClient(dep.getChainConfigId()),
                    dep.getContractAddress(), new Function("isFrozen", List.of(new Address(address)),
                            List.of(new TypeReference<Bool>() {})));
            frozen = out.isEmpty() ? null : (Boolean) out.get(0).getValue();
        } catch (RuntimeException e) {
            log.warn("isFrozen({}) read failed on vault {}: {}", address, dep.getContractAddress(), e.getMessage());
            frozen = null;
        }
        if (frozen == null) {
            throw new ComplianceGateException("Could not read the on-chain freeze status of " + address
                    + " — refused (fail closed); retry once the chain node is reachable.");
        }
        return frozen ? "Wallet " + address + " is frozen on the vault" : null;
    }

    private String holdReasonOrUnavailable(AssetDeployment dep, String address) {
        try {
            return holdReason(dep, address);
        } catch (ComplianceGateException e) {
            return "Freeze status of " + address + " unavailable";
        }
    }

    /** Pre-flight {@code eth_call}: a contract revert becomes a meaningful API error before any
     *  tx is signed. An unavailable simulation does not block — the submission stays authoritative. */
    private void preflight(AssetDeployment dep, Function fn, String action) {
        evmContractService.simulateRevert(dep.getChainConfigId(), dep.getContractAddress(), fn)
                .ifPresent(reason -> {
                    throw VaultRevertReasons.translate(reason, action);
                });
    }

    private AssetDeployment requireDeployment(UUID deploymentId) {
        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (dep.getContractAddress() == null) {
            throw new IllegalStateException("Vault contract not yet deployed: deploymentId=" + deploymentId);
        }
        return dep;
    }

    private VaultRequest requirePendingRequest(AssetDeployment deployment, BigInteger requestId,
                                               VaultRequestType expectedType) {
        VaultRequest request = vaultRequestRepository
                .findByAssetIdAndChainConfigIdAndRequestId(
                        deployment.getAssetId(), deployment.getChainConfigId(), requestId)
                .or(() -> deployment.getChainConfigId() == null ? Optional.empty()
                        : vaultRequestRepository.findByAssetIdAndChainConfigIdIsNullAndRequestId(
                                deployment.getAssetId(), requestId))
                .orElseThrow(() -> new EntityNotFoundException(
                        "VaultRequest", "requestId", requestId.toString()));
        if (request.getChainConfigId() == null) {
            request.setChainConfigId(deployment.getChainConfigId()); // legacy row: attach the chain
        }
        if (expectedType != null && request.getRequestType() != expectedType) {
            throw new IllegalArgumentException("Vault request " + requestId + " is "
                    + request.getRequestType() + ", not " + expectedType);
        }
        if (request.getRequestStatus() != VaultRequestStatus.PENDING) {
            throw new IllegalStateException("Vault request " + requestId + " is already "
                    + request.getRequestStatus());
        }
        // requestStatus alone no longer proves this request is free to act on: fulfil/cancel now
        // leave it PENDING while a submitted tx awaits confirmation (see #fulfill,
        // #cancelExpectedRequest) — an in-flight request must not be re-submitted.
        if (request.getFulfilledTx() != null || request.getCancelledTx() != null) {
            throw new IllegalStateException("Vault request " + requestId
                    + " already has a submitted tx awaiting confirmation");
        }
        return request;
    }

    /** @param txId the {@code blockchain_transaction} tracking row's id (what callers of this
     *              service return to their own callers); {@code txHash} the actual on-chain
     *              transaction hash (what gets stored on {@code VaultRequest} so {@code
     *              VaultConfirmationListener} can poll it via {@code BlockchainTransactionService}). */
    private record SubmittedTx(UUID txId, String txHash) {}

    /**
     * Submits the on-chain transaction and publishes a {@link TokenAdminActionEvent} to the
     * audit log — the chokepoint every state-changing method in this class funnels through, so
     * vault-request fulfillment, cancellation and force-cancellation are all audited.
     */
    private SubmittedTx submitEvm(AssetDeployment dep, Function fn, String methodName, Map<String, Object> params,
                           UUID actorId, String actorRole) {
        if (dep.getChainConfigId() == null) {
            throw new IllegalStateException("Confirmed EVM deployment is missing chainConfigId: " + dep.getId());
        }
        String txHash = durableTransactions.submit(
                dep.getChainConfigId(), dep.getContractAddress(), fn, params);

        eventPublisher.publishEvent(new TokenAdminActionEvent(dep.getId(), methodName, actorId, actorRole, params));

        UUID txId = txService.record(txHash, methodName, dep.getId(), dep.getAssetId(),
                dep.getChain().name(), dep.getNetwork().name(), dep.getContractAddress(), params);
        return new SubmittedTx(txId, txHash);
    }
}
