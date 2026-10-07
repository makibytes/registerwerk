package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionAnnouncedEvent;
import de.makibytes.registerwerk.deployment.api.CouponLifecycleTransitionEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionCancelledEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionIssuerAttestationOverriddenEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionIssuerAttestedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionOperatorConfirmedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposalApprovedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposalRejectedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionProposedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionPayoutHeldEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementBlockedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSignOffVoidedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSnapshotBlockedEvent;
import de.makibytes.registerwerk.corporateactions.web.dto.ProposeCorporateActionRequest;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository;
import de.makibytes.registerwerk.deployment.api.CouponStatus;
import de.makibytes.registerwerk.deployment.api.HolderKind;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityGate;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.Money;
import de.makibytes.registerwerk.shared.RegisterClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates corporate-action lifecycle.
 *
 * <p>Two families of creation: COUPON/REDEMPTION stay system-raised (see {@code CouponPaymentJob}/
 * {@code BondMaturityJob}), created directly via {@link #announce}. DIVIDEND/SPLIT/CALL are
 * issuer-proposed via {@link #propose} — a real human actor, reviewed by an operator
 * ({@link #approveProposal}/{@link #rejectProposal}) before joining the same ANNOUNCED pipeline.
 *
 * <p>Settlement approval is a two-party control: the issuer attests the underlying obligation is
 * ready ({@link #attestSettlementAsIssuer}, or an audited operator {@link #overrideIssuerAttestation}
 * for an issuer who never logs in), then an operator confirms register/on-chain execution
 * readiness ({@link #confirmSettlementAsOperator}) — refused until the issuer half is set.
 *
 * <p>Settlement dispatch is token-standard-specific:
 * ERC-3525 → Erc3525AdminService, ERC-4626/7540 → vault NAV strike,
 * DAML bonds → CantonBondOperations.payCoupon/redeem/earlyCall, SPL-2022 → SolanaTokenService.
 */
@Service
@Transactional
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    /** Nil UUID used as the actor ID for system-initiated actions — mirrors
     *  {@code CouponPaymentJob}/{@code BondMaturityJob}'s own constant. */
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    /** What a holder (investor) may see — an issuer's unreviewed or rejected proposal is a draft,
     *  not a register fact, so it's excluded from {@link #findByAssetForHolder}. */
    private static final Set<CorporateAction.Status> HOLDER_VISIBLE_STATUSES =
            EnumSet.complementOf(EnumSet.of(CorporateAction.Status.PROPOSED, CorporateAction.Status.REJECTED));

    private final CorporateActionRepository repository;
    private final CorporateActionEntryRepository entryRepository;
    private final RecordDatePositionResolver positionResolver;
    private final CorporateActionSettlementWriter settlementWriter;
    private final AssetCouponPaymentRepository couponPaymentRepository;
    private final CorporateActionProposalValidator proposalValidator;
    private final ApplicationEventPublisher events;
    private final PartyEligibilityGate partyGate;
    private final EntityTaskPort entityTasks;
    private final IsolatedTransactionExecutor isolated;
    private final FinalityGate finalityGate;
    private final RegisterFreshnessGate registerFreshnessGate;
    private final AssetBondTermsRepository bondTermsRepository;
    private final RegisterClock registerClock;

    CorporateActionService(CorporateActionRepository repository,
                            CorporateActionEntryRepository entryRepository,
                            RecordDatePositionResolver positionResolver,
                            CorporateActionSettlementWriter settlementWriter,
                            AssetCouponPaymentRepository couponPaymentRepository,
                            CorporateActionProposalValidator proposalValidator,
                            ApplicationEventPublisher events,
                            PartyEligibilityGate partyGate,
                            EntityTaskPort entityTasks,
                            FinalityGate finalityGate,
                            RegisterFreshnessGate registerFreshnessGate,
                            AssetBondTermsRepository bondTermsRepository,
                            RegisterClock registerClock,
                            IsolatedTransactionExecutor isolated) {
        this.repository = repository;
        this.entryRepository = entryRepository;
        this.positionResolver = positionResolver;
        this.settlementWriter = settlementWriter;
        this.couponPaymentRepository = couponPaymentRepository;
        this.proposalValidator = proposalValidator;
        this.events = events;
        this.partyGate = partyGate;
        this.entityTasks = entityTasks;
        this.isolated = isolated;
        this.finalityGate = finalityGate;
        this.registerFreshnessGate = registerFreshnessGate;
        this.bondTermsRepository = bondTermsRepository;
        this.registerClock = registerClock;
    }

    /** System-raised creation path — {@code CouponPaymentJob}/{@code BondMaturityJob} only. */
    public CorporateAction announce(CorporateAction action) {
        action.setStatus(CorporateAction.Status.ANNOUNCED);
        CorporateAction saved = repository.save(action);
        log.info("Corporate action announced: id={} type={} assetId={}", saved.getId(), saved.getActionType(), saved.getAssetId());
        events.publishEvent(new CorporateActionAnnouncedEvent(
                saved.getId(), null, "SYSTEM", saved.getAssetId(), saved.getActionType()));
        return saved;
    }

    /**
     * Issuer-initiated creation path for DIVIDEND/SPLIT/CALL — validated by
     * {@link CorporateActionProposalValidator}, starts life {@code PROPOSED}, invisible to
     * {@code findReadyToCompute}/{@code findDueForSettlement} until an operator approves it.
     * {@code actorId} is the real proposing issuer — the first non-system {@code initiatedBy}
     * this system has ever recorded.
     */
    public CorporateAction propose(UUID assetId, ProposeCorporateActionRequest request, UUID actorId, String actorRole) {
        registerFreshnessGate.requireRegisterOpen(assetId, "Corporate action proposal");
        CorporateAction action = proposalValidator.validateAndBuild(assetId, request);
        action.setInitiatedBy(actorId);
        action.setStatus(CorporateAction.Status.PROPOSED);
        CorporateAction saved = repository.save(action);

        events.publishEvent(new CorporateActionProposedEvent(
                saved.getId(), actorId, actorRole, saved.getAssetId(), saved.getActionType()));
        log.info("Corporate action proposed: id={} type={} assetId={} proposedBy={}",
                saved.getId(), saved.getActionType(), saved.getAssetId(), actorId);
        return saved;
    }

    /** Operator approves an issuer's proposal — {@code PROPOSED} → {@code ANNOUNCED}, joining the
     *  existing pipeline unchanged. Fires both the proposal-review event and the pre-existing
     *  {@link CorporateActionAnnouncedEvent} so "every announced action" audit consumers see it. */
    public CorporateAction approveProposal(UUID corporateActionId, UUID actorId, String actorRole) {
        CorporateAction ca = requireProposed(corporateActionId);
        registerFreshnessGate.requireRegisterOpen(ca.getAssetId(), "Corporate action approval");
        // T3-12: two-party control — an admin who proposed while impersonating the issuer (the
        // token's sub stays the admin's own id) must not approve that proposal themselves.
        if (actorId != null && actorId.equals(ca.getInitiatedBy())) {
            throw new AccessDeniedException("Corporate action " + corporateActionId
                    + " was proposed by the same user — a different operator must approve it.");
        }
        // T3-06: the record date must still be ahead at approval; the snapshot is fixed as of its end.
        String recordDateRefusal = CorporateActionProposalValidator.recordDateRefusal(ca.getRecordDate(), registerClock.today());
        if (recordDateRefusal != null) {
            throw new IllegalStateException(recordDateRefusal + " Reject this proposal so the issuer can re-propose it.");
        }
        ca.setStatus(CorporateAction.Status.ANNOUNCED);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionProposalApprovedEvent(corporateActionId, actorId, actorRole, ca.getInitiatedBy()));
        events.publishEvent(new CorporateActionAnnouncedEvent(
                saved.getId(), actorId, actorRole, saved.getAssetId(), saved.getActionType()));
        log.info("Corporate action proposal approved: id={} approver={}", corporateActionId, actorId);
        return saved;
    }

    /** Operator rejects an issuer's proposal — terminal, distinct from {@code cancel} (which
     *  unwinds an already-live announcement). */
    public CorporateAction rejectProposal(UUID corporateActionId, String reason, UUID actorId, String actorRole) {
        CorporateAction ca = requireProposed(corporateActionId);
        ca.setStatus(CorporateAction.Status.REJECTED);
        ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + "rejected: " + reason);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionProposalRejectedEvent(corporateActionId, actorId, actorRole, reason));
        log.info("Corporate action proposal rejected: id={} reason={}", corporateActionId, reason);
        return saved;
    }

    /** Issuer withdraws their own still-unreviewed proposal. {@code assetId} must match the
     *  action's own — the controller's {@code @PreAuthorize} only checks that the caller owns
     *  {@code assetId} from the path, not that {@code corporateActionId} actually belongs to it. */
    public CorporateAction withdrawProposal(UUID assetId, UUID corporateActionId, UUID actorId) {
        CorporateAction ca = requireProposed(corporateActionId);
        requireBelongsToAsset(ca, assetId);
        ca.setStatus(CorporateAction.Status.CANCELLED);
        ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + "withdrawn by proposer");
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionCancelledEvent(corporateActionId, actorId, "ISSUER", "withdrawn by proposer"));
        log.info("Corporate action proposal withdrawn: id={} by={}", corporateActionId, actorId);
        return saved;
    }

    /** Guards against an issuer authorized for {@code assetId} (via
     *  {@code @assetAccessChecker.canActAsIssuer(#assetId, ...)} at the controller) supplying a
     *  {@code corporateActionId} that actually belongs to a <em>different</em> asset — the
     *  {@code @PreAuthorize} SpEL only ever checks the path's own {@code assetId}. */
    private void requireBelongsToAsset(CorporateAction ca, UUID assetId) {
        if (!ca.getAssetId().equals(assetId)) {
            throw new EntityNotFoundException("CorporateAction", ca.getId());
        }
    }

    private CorporateAction requireProposed(UUID corporateActionId) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() != CorporateAction.Status.PROPOSED) {
            throw new IllegalStateException("Corporate action " + corporateActionId + " is not PROPOSED (status=" + ca.getStatus() + ")");
        }
        return ca;
    }

    @Transactional(readOnly = true)
    public List<CorporateAction> findByAsset(UUID assetId) {
        return repository.findByAssetId(assetId);
    }

    /** Investor-facing list for {@code MeCorporateActionController} — excludes an issuer's own
     *  {@code PROPOSED}/{@code REJECTED} drafts via {@link CorporateActionRepository#findByAssetIdAndStatusIn},
     *  the dedicated query this method exists to actually use (see that repository method's javadoc). */
    @Transactional(readOnly = true)
    public List<CorporateAction> findByAssetForHolder(UUID assetId) {
        return repository.findByAssetIdAndStatusIn(assetId, HOLDER_VISIBLE_STATUSES);
    }

    @Transactional(readOnly = true)
    public List<CorporateAction> findProposalsPendingReview() {
        return repository.findByStatusOrderByCreatedAtAsc(CorporateAction.Status.PROPOSED);
    }

    /**
     * Issuer attestation — the first of the two required parties before settlement can be
     * confirmed. Wave 0b C6: the attestation covers the <em>computed amounts</em>, so it is only possible
     * once the action is {@code COMPUTED} (never while ANNOUNCED / SNAPSHOT_BLOCKED, when there is nothing to
     * attest) and it is bound to the digest of (entries, total, rounding residual) the issuer attested; a later
     * re-snapshot voids it. No step-up (see this class's javadoc for why — {@code frontend-customer} has no
     * step-up UI today).
     *
     * <p>T3-12: refused (403) when {@code callerIsOperator} — a REGISTRY_ADMIN, or an admin
     * impersonating the issuer. Operators attest only through the audited, step-up-gated
     * {@link #overrideIssuerAttestation}; otherwise one operator could supply both halves.
     */
    public CorporateAction attestSettlementAsIssuer(UUID assetId, UUID corporateActionId, String attestationReference,
                                                    UUID actorId, String actorRole, boolean callerIsOperator) {
        return attestSettlementAsIssuer(assetId, corporateActionId, attestationReference, actorId, actorRole,
                callerIsOperator, null);
    }

    /** @param expectedPayoutDigest the digest the attester reviewed (optional); a different current digest means
     *                              the amounts were re-computed since, and the attestation is refused (409) */
    public CorporateAction attestSettlementAsIssuer(UUID assetId, UUID corporateActionId, String attestationReference,
                                                    UUID actorId, String actorRole, boolean callerIsOperator,
                                                    String expectedPayoutDigest) {
        if (callerIsOperator) {
            throw new AccessDeniedException("Operators cannot attest as the issuer — use the operator "
                    + "override-attestation (step-up, reason, separately audited) instead.");
        }
        CorporateAction ca = requireSignable(corporateActionId);
        requireBelongsToAsset(ca, assetId);
        String digest = requireCurrentPayoutDigest(ca, expectedPayoutDigest);
        ca.setIssuerAttestedBy(actorId);
        ca.setIssuerAttestedAt(Instant.now());
        ca.setIssuerAttestationRef(attestationReference);
        ca.setIssuerAttestedDigest(digest);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionIssuerAttestedEvent(corporateActionId, actorId, actorRole, attestationReference));
        log.info("Corporate action issuer-attested: id={} attester={} digest={}", corporateActionId, actorId, digest);
        return saved;
    }

    /**
     * Operator override of the issuer-attestation requirement — the escape hatch for an issuer
     * who never logs in to attest. Always a distinct, separately-audited event from a genuine
     * attestation (see {@link CorporateActionIssuerAttestationOverriddenEvent}'s javadoc). Bound to the computed
     * amounts exactly like the issuer's own attestation (C6).
     */
    public CorporateAction overrideIssuerAttestation(UUID corporateActionId, String reason, UUID actorId, String actorRole) {
        CorporateAction ca = requireSignable(corporateActionId);
        String digest = requireCurrentPayoutDigest(ca, null);
        ca.setIssuerAttestedBy(actorId);
        ca.setIssuerAttestedAt(Instant.now());
        ca.setIssuerAttestationRef("OPERATOR_OVERRIDE: " + reason);
        ca.setIssuerAttestedDigest(digest);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionIssuerAttestationOverriddenEvent(corporateActionId, actorId, actorRole, reason));
        log.info("Corporate action issuer-attestation overridden by operator: id={} operator={} reason={}",
                corporateActionId, actorId, reason);
        return saved;
    }

    /** C6: a sign-off covers computed amounts, so only a COMPUTED action can be signed (not even by an operator). */
    private CorporateAction requireSignable(UUID corporateActionId) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() != CorporateAction.Status.COMPUTED) {
            throw new IllegalStateException("Corporate action " + corporateActionId + " is not COMPUTED (status="
                    + ca.getStatus() + ") — the issuer attestation and the operator confirmation cover the computed "
                    + "amounts, so they are only possible once the entitlements have been computed.");
        }
        return ca;
    }

    /**
     * The digest of the entitlements as they stand now. Refuses (409) when they no longer equal the digest stored at
     * compute time (somebody changed an entry after the snapshot) or the digest the signer reviewed.
     */
    private String requireCurrentPayoutDigest(CorporateAction ca, String expectedPayoutDigest) {
        String current = currentPayoutDigest(ca);
        if (ca.getPayoutDigest() == null || !ca.getPayoutDigest().equals(current)) {
            throw new IllegalStateException("The computed entitlements of corporate action " + ca.getId()
                    + " changed since they were computed (stored digest " + ca.getPayoutDigest() + ", current "
                    + current + ") — a re-snapshot is required before anybody can sign them off.");
        }
        if (expectedPayoutDigest != null && !expectedPayoutDigest.equalsIgnoreCase(current)) {
            throw new IllegalStateException("Payout digest mismatch for corporate action " + ca.getId()
                    + ": the amounts were re-computed after you reviewed them (reviewed " + expectedPayoutDigest
                    + ", current " + current + ") — review the current amounts and sign again.");
        }
        return current;
    }

    private String currentPayoutDigest(CorporateAction ca) {
        return CorporateActionPayoutDigest.of(ca, entryRepository.findByCorporateActionId(ca.getId()));
    }

    /**
     * Voids an earlier attestation and/or confirmation (C6): the amounts were re-snapshotted, or no longer match the
     * digest the parties signed. Audited; the payout does not start until both sign the amounts as they now are.
     *
     * @return true when there was a sign-off to void
     */
    private boolean voidSignOffs(CorporateAction ca, String reason) {
        boolean issuer = ca.getIssuerAttestedAt() != null || ca.getIssuerAttestedBy() != null
                || ca.getIssuerAttestedDigest() != null;
        boolean operator = ca.getDualControlApprovedAt() != null || ca.getDualControlApproverId() != null
                || ca.getOperatorConfirmedDigest() != null;
        if (!issuer && !operator) {
            return false;
        }
        ca.setIssuerAttestedBy(null);
        ca.setIssuerAttestedAt(null);
        ca.setIssuerAttestationRef(null);
        ca.setIssuerAttestedDigest(null);
        ca.setDualControlApproverId(null);
        ca.setDualControlApprovedAt(null);
        ca.setOperatorConfirmedDigest(null);
        repository.save(ca);
        events.publishEvent(new CorporateActionSignOffVoidedEvent(ca.getId(), reason, issuer, operator));
        log.warn("Corporate action {} sign-off voided ({}): issuerHadAttested={} operatorHadConfirmed={}",
                ca.getId(), reason, issuer, operator);
        return true;
    }

    /**
     * Operator confirmation — the second of the two required parties. Refused unless the action is {@code COMPUTED}
     * (C6), while the issuer half is missing (issuer-first ordering, see this class's javadoc) or covers other amounts
     * than the ones now computed, and while the confirmer is the same actor as the attester. Gated on
     * {@link GatedOperation#CORPORATE_ACTION_SETTLEMENT_CONFIRM} immediately before the write —
     * {@code currentLevel} is passed as {@code FINALIZED} unconditionally, the same reasoning
     * {@code RegisterTransferService} uses: entitlements are computed from {@code AssetHolder.nominalAmount}, which
     * {@code HolderDataService} only ever populates from FINALIZED transfers.
     */
    public CorporateAction confirmSettlementAsOperator(UUID corporateActionId, UUID actorId, String actorRole) {
        return confirmSettlementAsOperator(corporateActionId, actorId, actorRole, null);
    }

    /** @param expectedPayoutDigest the digest the operator reviewed (optional), see {@link #attestSettlementAsIssuer} */
    public CorporateAction confirmSettlementAsOperator(UUID corporateActionId, UUID actorId, String actorRole,
                                                       String expectedPayoutDigest) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() == CorporateAction.Status.SETTLED || ca.getStatus() == CorporateAction.Status.CLOSED
                || ca.getStatus() == CorporateAction.Status.CANCELLED) {
            throw new IllegalStateException("Corporate action " + corporateActionId + " is already " + ca.getStatus());
        }
        registerFreshnessGate.requireRegisterOpen(ca.getAssetId(), "Corporate action settlement confirmation");
        if (ca.getStatus() != CorporateAction.Status.COMPUTED) {
            throw new IllegalStateException("Corporate action " + corporateActionId + " is not COMPUTED (status="
                    + ca.getStatus() + (ca.getStatus() == CorporateAction.Status.SNAPSHOT_BLOCKED
                    ? ": " + ca.getSnapshotBlockedReason() : "") + ") — the operator confirmation covers the "
                    + "computed amounts, so it is only possible once the entitlements have been computed.");
        }
        if (ca.getIssuerAttestedAt() == null) {
            throw new IllegalStateException(
                    "Corporate action " + corporateActionId + " has not been attested by its issuer yet — "
                            + "operator confirmation requires the issuer's attestation first (or an operator override).");
        }
        if (actorId != null && actorId.equals(ca.getIssuerAttestedBy())) {
            throw new IllegalArgumentException(
                    "Operator confirmer must be a different actor than whoever attested as issuer.");
        }
        String digest = requireCurrentPayoutDigest(ca, expectedPayoutDigest);
        if (!digest.equals(ca.getIssuerAttestedDigest())) {
            throw new IllegalStateException("The issuer attestation of corporate action " + corporateActionId
                    + " covers other amounts than the ones now computed (attested digest "
                    + ca.getIssuerAttestedDigest() + ", current " + digest + ") — the issuer must attest again.");
        }
        // T2-18: no settlement approval on a register that is not reconciled with the chain. A
        // record date still in the future is enforced by the snapshot itself, so only BLOCKED counts then.
        LocalDate recordDate = ca.getRecordDate() != null && ca.getRecordDate().isBefore(registerClock.today())
                ? ca.getRecordDate() : null;
        Optional<String> registerBlocked = registerFreshnessGate.blockedReason(ca.getAssetId(), recordDate);
        if (registerBlocked.isPresent()) {
            throw new IllegalStateException("Settlement confirmation refused for corporate action "
                    + corporateActionId + ": " + registerBlocked.get());
        }
        finalityGate.require(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM, ca.getAssetId(),
                resolveTokenStandard(corporateActionId), FinalityLevel.FINALIZED);

        ca.setDualControlApproverId(actorId);
        ca.setDualControlApprovedAt(Instant.now());
        ca.setOperatorConfirmedDigest(digest);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionOperatorConfirmedEvent(
                corporateActionId, actorId, actorRole, ca.getIssuerAttestedBy()));
        log.info("Corporate action operator-confirmed: id={} confirmer={} digest={}", corporateActionId, actorId, digest);
        return saved;
    }

    /**
     * Manually records settlement for a corporate action with no automated on-chain settlement
     * adapter — {@code CorporateActionSettlementListener} only dispatches Canton bond coupons/
     * redemptions/early-calls automatically; SPLIT (no on-chain split primitive exists on any
     * supported token standard), ERC-3525/ERC-4626/ERC-7540, and every other standard just log
     * "requires operator review" and have no other path out of AWAITING_SETTLEMENT. The operator
     * attests here that the payment/action was executed through whatever channel actually did it
     * and supplies a reference for the audit trail. Gated by
     * {@code @RequiresStepUp(requireSecondApprover = true)} at the controller — this is exactly
     * as consequential as the automated path from the register's point of view.
     */
    public CorporateAction markSettledManually(UUID corporateActionId, String reference, UUID actorId, String actorRole) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() != CorporateAction.Status.AWAITING_SETTLEMENT) {
            throw new IllegalStateException(
                    "Corporate action " + corporateActionId + " is not AWAITING_SETTLEMENT (status=" + ca.getStatus() + ")");
        }
        registerFreshnessGate.requireRegisterOpen(ca.getAssetId(), "Manual corporate action settlement");
        // H6: a holder that is not (or no longer) eligible is held, not paid - the others are recorded as paid.
        holdIneligibleEntries(ca);
        finalityGate.require(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM, ca.getAssetId(),
                resolveTokenStandard(corporateActionId), FinalityLevel.FINALIZED);
        settlementWriter.markSettled(corporateActionId, reference, actorId, actorRole);
        return repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
    }

    /**
     * Cancels a corporate action — {@code Status.CANCELLED} has always
     * existed and every settlement/idempotency query already excludes it, but until now nothing
     * ever set it: a mistakenly-raised or no-longer-applicable action (e.g. a coupon
     * auto-created against the wrong asset, or one superseded by a corrected re-announcement)
     * had no way out of the pipeline. Refused once the action has reached AWAITING_SETTLEMENT
     * or beyond — at that point money/tokens may already be in flight or settled, and reversing
     * it is an operational/legal matter outside this method's scope, not a simple status flip.
     * Operator-only (unlike proposal withdrawal, unwinding a live register-affecting
     * announcement is a registrar act) — gated by
     * {@code @RequiresStepUp(requireSecondApprover = true)} at the controller.
     */
    public CorporateAction cancel(UUID corporateActionId, String reason, UUID actorId, String actorRole) {
        CorporateAction ca = repository.findById(corporateActionId)
                .orElseThrow(() -> new EntityNotFoundException("CorporateAction", corporateActionId));
        if (ca.getStatus() == CorporateAction.Status.AWAITING_SETTLEMENT
                || ca.getStatus() == CorporateAction.Status.SETTLED
                || ca.getStatus() == CorporateAction.Status.CLOSED
                || ca.getStatus() == CorporateAction.Status.CANCELLED) {
            throw new IllegalStateException(
                    "Corporate action " + corporateActionId + " cannot be cancelled from status " + ca.getStatus());
        }
        ca.setStatus(CorporateAction.Status.CANCELLED);
        ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + "cancelled: " + reason);
        CorporateAction saved = repository.save(ca);

        events.publishEvent(new CorporateActionCancelledEvent(corporateActionId, actorId, actorRole, reason));
        log.info("Corporate action cancelled: id={} reason={}", corporateActionId, reason);
        return saved;
    }

    /** Daily job: transition ANNOUNCED → RECORD_DATE_SET → COMPUTED once the record date is over,
     *  dispatch settlement when due, close out actions that finished settling, and flag overdue
     *  coupons. 06:00 register time — after CouponPaymentJob (05:30) and BondMaturityJob (05:45),
     *  so actions they raise the same morning are processed in this run (T3-05).
     *
     *  <p>H8: the job itself holds NO transaction. Every item runs in its own {@code REQUIRES_NEW} transaction
     *  ({@link IsolatedTransactionExecutor}, the {@code TradeTimeoutProcessor} pattern), re-loaded by id so it sees
     *  committed state: one failing action rolls back only itself, never the day's other snapshots and settlements. */
    @SchedulerLock(name = "corporateActionDailyTransitions", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 6 * * *", zone = "${registerwerk.register.time-zone:Europe/Berlin}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processDailyTransitions() {
        LocalDate today = registerClock.today();

        for (UUID id : idsOf(repository.findReadyToCompute(today))) {
            runItem("advance", id, () -> advanceOne(id));
        }
        for (UUID id : idsOf(repository.findDueForSettlement(today))) {
            runItem("settle", id, () -> settleOne(id));
        }
        for (UUID id : idsOf(repository.findByStatus(CorporateAction.Status.SETTLED))) {
            runItem("close", id, () -> closeOne(id));
        }
        for (UUID id : idsOf(repository.findOverdueCoupons(today))) {
            runItem("coupon-overdue", id, () -> flagOverdueCoupon(id, today));
        }
    }

    private static List<UUID> idsOf(List<CorporateAction> actions) {
        return actions.stream().map(CorporateAction::getId).toList();
    }

    private void runItem(String step, UUID corporateActionId, IsolatedTransactionExecutor.Work work) {
        try {
            isolated.run(work);
        } catch (Exception e) {
            log.error("Corporate action {} step '{}' failed (its own transaction was rolled back; the other "
                    + "actions are unaffected): {}", corporateActionId, step, e.getMessage(), e);
        }
    }

    /** One ANNOUNCED / SNAPSHOT_BLOCKED action whose record date is over: snapshot it, or park it visibly. */
    private void advanceOne(UUID id) {
        CorporateAction ca = repository.findById(id).orElse(null);
        if (ca == null || (ca.getStatus() != CorporateAction.Status.ANNOUNCED
                && ca.getStatus() != CorporateAction.Status.SNAPSHOT_BLOCKED)) {
            return; // moved on (or cancelled) since the scan
        }
        if (registerFreshnessGate.isRegisterFrozen(ca.getAssetId())) {
            log.warn("Corporate action {} not advanced: register of asset {} is frozen/transferred (T3-07)",
                    ca.getId(), ca.getAssetId());
            return;
        }
        // T2-18 (SRE veto: no silent refusal): a register that is BLOCKED or not reconciled
        // since the record date must not be snapshotted; the action is parked visibly as
        // SNAPSHOT_BLOCKED (audited, metric + alert) and retried on the next run.
        Optional<String> registerBlocked = registerFreshnessGate.blockedReason(ca.getAssetId(), ca.getRecordDate());
        if (registerBlocked.isPresent()) {
            markSnapshotBlocked(ca, registerBlocked.get());
            return;
        }
        snapshotEntriesAndCompute(ca);
    }

    /**
     * One COMPUTED action whose payment date has come. Starts the payout only when BOTH parties have signed the
     * amounts as they are now (C6): a sign-off that carries another digest than the entries do (re-snapshot, an
     * entry changed, or a sign-off given before amounts were bound) is voided, audited, and nothing is paid.
     */
    private void settleOne(UUID id) {
        CorporateAction ca = repository.findById(id).orElse(null);
        if (ca == null || ca.getStatus() != CorporateAction.Status.COMPUTED) {
            return; // moved on since the scan (the scan already selected on the payment date)
        }
        if (registerFreshnessGate.isRegisterFrozen(ca.getAssetId())) {
            log.warn("Corporate action {} settlement skipped: register of asset {} is frozen/transferred (T3-07)",
                    ca.getId(), ca.getAssetId());
            return;
        }
        if (ca.getIssuerAttestedAt() == null || ca.getDualControlApproverId() == null) {
            log.warn("Corporate action {} is due for settlement (paymentDate={}) but is missing {} — "
                            + "skipping until both parties have signed off.",
                    ca.getId(), ca.getPaymentDate(),
                    ca.getIssuerAttestedAt() == null && ca.getDualControlApproverId() == null
                            ? "both the issuer attestation and the operator confirmation"
                            : ca.getIssuerAttestedAt() == null ? "the issuer attestation" : "the operator confirmation");
            return;
        }
        String current = currentPayoutDigest(ca);
        if (!current.equals(ca.getPayoutDigest()) || !current.equals(ca.getIssuerAttestedDigest())
                || !current.equals(ca.getOperatorConfirmedDigest())) {
            voidSignOffs(ca, "the signed payout digest does not match the entitlements as they are now (current "
                    + current + ", computed " + ca.getPayoutDigest() + ", issuer " + ca.getIssuerAttestedDigest()
                    + ", operator " + ca.getOperatorConfirmedDigest() + ")");
            return;
        }
        settle(ca);
    }

    private void closeOne(UUID id) {
        CorporateAction ca = repository.findById(id).orElse(null);
        if (ca == null || ca.getStatus() != CorporateAction.Status.SETTLED) {
            return;
        }
        if (entryRepository.existsHeldWithEntitlement(ca.getId())) {
            if (!ca.isHeldOutstanding()) {
                ca.setHeldOutstanding(true);
                ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "")
                        + "held entitlements outstanding (nominee pool PARK-T2-18, or holders held by the "
                        + "eligibility gate - see the entries)");
                repository.save(ca);
                log.warn("Corporate action {} not closed: held entitlements are unresolved", ca.getId());
            }
            return;
        }
        ca.setStatus(CorporateAction.Status.CLOSED);
        repository.save(ca);
        log.info("Corporate action closed: id={}", ca.getId());
    }

    private void markSnapshotBlocked(CorporateAction ca, String reason) {
        boolean changed = ca.getStatus() != CorporateAction.Status.SNAPSHOT_BLOCKED
                || !Objects.equals(reason, ca.getSnapshotBlockedReason());
        ca.setStatus(CorporateAction.Status.SNAPSHOT_BLOCKED);
        ca.setSnapshotBlockedReason(reason);
        repository.save(ca);
        if (changed) {
            events.publishEvent(new CorporateActionSnapshotBlockedEvent(ca.getId(), ca.getAssetId(), reason));
        }
        log.error("Corporate action {} snapshot BLOCKED (recordDate={}): {}", ca.getId(), ca.getRecordDate(), reason);
    }

    /**
     * T3-05: a coupon whose action is unsettled after its payment date is {@code OVERDUE}
     * (operator-visible; customers see "payment pending"); only once the bond's interest grace
     * period ({@code interestGraceDays}, default 30) has also passed is it {@code MISSED}. Before,
     * it was MISSED the morning after the payment date — publicly, and for every coupon.
     *
     * <p>H6: a coupon whose action the SYSTEM is holding back (snapshot blocked, register frozen, settlement held)
     * is neither OVERDUE nor MISSED: that would present a registry-side delay as the issuer's non-payment. 9A-04R: the
     * same holds for an action that waits for the OPERATOR (confirmation missing, settlement not dispatched); and
     * every real OVERDUE / MISSED change publishes one audited {@link CouponLifecycleTransitionEvent}.
     */
    private void flagOverdueCoupon(UUID corporateActionId, LocalDate today) {
        CorporateAction overdue = repository.findById(corporateActionId).orElse(null);
        if (overdue == null || overdue.getCouponPaymentId() == null || overdue.getPaymentDate() == null
                || overdue.getStatus() == CorporateAction.Status.SETTLED
                || overdue.getStatus() == CorporateAction.Status.CLOSED
                || overdue.getStatus() == CorporateAction.Status.CANCELLED
                || !overdue.getPaymentDate().isBefore(today)) {
            return;
        }
        CorporateActionBlocks.Responsibility who = CorporateActionBlocks.responsibleSide(overdue,
                registerFreshnessGate.isRegisterFrozen(overdue.getAssetId()));
        if (!who.countsAsIssuerNonPayment()) {
            log.warn("Coupon of corporate action {} is past its payment date but NOT flagged overdue/missed: it waits "
                    + "for the {} side - {}", overdue.getId(), who.side(), who.cause());
            if (who.side() == CorporateActionBlocks.Side.OPERATOR) {
                // 9A-04R: operator slowness is surfaced to the operator, never as the issuer's missed coupon.
                openIssuerTask(overdue.getAssetId(), CorporateActionBlocks.TASK_COUPON_OPERATOR_PENDING,
                        overdue.getId().toString(), "The coupon payment " + overdue.getCouponPaymentId()
                                + " (corporate action " + overdue.getId() + ", payment date " + overdue.getPaymentDate()
                                + ") is waiting for the operator - " + who.cause()
                                + ". It is NOT flagged overdue or missed.");
            }
            return;
        }
        couponPaymentRepository.findById(overdue.getCouponPaymentId()).ifPresent(payment -> {
            if (payment.getCouponStatus() != CouponStatus.SCHEDULED && payment.getCouponStatus() != CouponStatus.OVERDUE) {
                return;
            }
            int grace = bondTermsRepository.findById(payment.getAssetId())
                    .map(AssetBondTerms::getInterestGraceDays).orElse(0);
            if (today.isAfter(overdue.getPaymentDate().plusDays(grace))) {
                couponTransition(payment, overdue, CouponStatus.MISSED, grace,
                        "unsettled after payment date " + overdue.getPaymentDate() + " + interest grace of " + grace
                                + " day(s)", CorporateActionBlocks.TASK_COUPON_MISSED);
                log.warn("Coupon missed: paymentId={} assetId={} paymentDate={} — unsettled after the {}-day grace period",
                        payment.getId(), payment.getAssetId(), overdue.getPaymentDate(), grace);
            } else if (payment.getCouponStatus() == CouponStatus.SCHEDULED) {
                couponTransition(payment, overdue, CouponStatus.OVERDUE, grace,
                        "unsettled after payment date " + overdue.getPaymentDate() + " (grace until "
                                + overdue.getPaymentDate().plusDays(grace) + ")", CorporateActionBlocks.TASK_COUPON_OVERDUE);
                log.warn("Coupon overdue: paymentId={} assetId={} paymentDate={} (grace until {})",
                        payment.getId(), payment.getAssetId(), overdue.getPaymentDate(),
                        overdue.getPaymentDate().plusDays(grace));
            }
        });
    }

    /** 9A-04R: the status change is audited (and the operator told) only when it really changes. */
    private void couponTransition(de.makibytes.registerwerk.deployment.api.AssetCouponPayment payment,
                                  CorporateAction action, CouponStatus to, int grace, String cause, String taskKind) {
        CouponStatus from = payment.getCouponStatus();
        if (from == to) {
            return;
        }
        payment.setCouponStatus(to);
        couponPaymentRepository.save(payment);
        events.publishEvent(new CouponLifecycleTransitionEvent(payment.getId(), payment.getAssetId(), from, to, cause,
                action.getId(), grace));
        openIssuerTask(payment.getAssetId(), taskKind, payment.getId().toString(),
                "Coupon " + payment.getId() + " moved " + from + " -> " + to + ": " + cause + ".");
    }

    private void openIssuerTask(UUID assetId, String kind, String refId, String detail) {
        registerFreshnessGate.issuerOf(assetId).ifPresent(issuerId -> entityTasks.open(issuerId, kind, refId, detail, null));
    }

    /**
     * Snapshots each holder's position <em>as of the end of the record date</em> (T3-06, see
     * {@link RecordDatePositionResolver}) as a {@code CorporateActionEntry} and, when the action
     * carries a known {@code amountPerUnit} (coupons/interest payments), computes each entry's
     * {@code entitlementAmount} and the action's aggregate {@code totalAmount}, transitioning to
     * COMPUTED. Actions without a per-unit amount (splits, calls, etc.) still get COMPUTED — there
     * is simply nothing to compute — since RECORD_DATE_SET → COMPUTED is otherwise a dead-end
     * status no code ever advances past. When the as-of positions cannot be established yet (chain
     * transfers before the cut-off not final, a wallet unmapped at the record date) the action is
     * SNAPSHOT_BLOCKED and retried, still against the same record date.
     *
     * <p>Rounding (T3-05): each entitlement is {@code amountPerUnit × nominal} rounded HALF_UP (Wave 5a,
     * {@link Money}: ICMA Rule 251 / kaufmaennische Rundung) to the currency's minor unit; {@code totalAmount} is the sum of the rounded payable entitlements
     * and {@code roundingResidual} = Σ unrounded − Σ rounded is kept for the operator confirmation.
     *
     * <p>A nominee-pool holder's entry (T2-18) is snapshotted with its entitlement but marked
     * {@code HELD_LOOK_THROUGH} and left out of {@code totalAmount}: who is entitled to payments on
     * pledged/escrowed units is undecided (PARK-T2-18), so they are neither paid to the pool nor
     * silently dropped.
     */
    private void snapshotEntriesAndCompute(CorporateAction ca) {
        if (entryRepository.existsByCorporateActionId(ca.getId())) {
            return; // already snapshotted (defensive — processDailyTransitions runs at most daily)
        }
        RecordDatePositionResolver.Resolution resolution =
                positionResolver.resolve(ca.getAssetId(), registerClock.endOfDay(ca.getRecordDate()));
        if (resolution.blockedReason().isPresent()) {
            markSnapshotBlocked(ca, resolution.blockedReason().get());
            return;
        }
        ca.setSnapshotBlockedReason(null);
        ca.setSettlementHoldReason(null);
        ca.setStatus(CorporateAction.Status.RECORD_DATE_SET);
        for (String note : resolution.notes()) {
            ca.setNotes((ca.getNotes() != null ? ca.getNotes() + " | " : "") + note);
        }
        repository.save(ca);
        log.info("Corporate action record date set: id={} recordDate={}", ca.getId(), ca.getRecordDate());

        BigDecimal amountPerUnit = ca.getAmountPerUnit();
        // Wave 5a: a cash action with a missing/unknown currency is refused (Money.minorUnits throws), never
        // paid at a guessed scale; non-cash actions (no amountPerUnit) need no currency.
        int minorUnits = amountPerUnit != null ? Money.minorUnits(ca.getCurrency()) : 0;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal unroundedTotal = BigDecimal.ZERO;
        List<CorporateActionEntry> snapshot = new ArrayList<>();

        for (RecordDatePositionResolver.Position position : resolution.positions()) {
            CorporateActionEntry entry = new CorporateActionEntry();
            entry.setCorporateActionId(ca.getId());
            entry.setAssetHolderId(position.assetHolderId());
            entry.setInvestorId(position.investorId());
            entry.setWalletAddress(position.walletAddress());
            BigDecimal nominal = position.nominal();
            entry.setNominalAtRecord(nominal);
            boolean held = position.holderKind() == HolderKind.NOMINEE_POOL;
            entry.setPayoutStatus(held ? CorporateActionEntry.PayoutStatus.HELD_LOOK_THROUGH
                    : CorporateActionEntry.PayoutStatus.PAYABLE);
            if (amountPerUnit != null) {
                BigDecimal unrounded = amountPerUnit.multiply(nominal);
                BigDecimal entitlement = Money.round(unrounded, minorUnits);
                entry.setEntitlementAmount(entitlement);
                if (!held) {
                    total = total.add(entitlement);
                    unroundedTotal = unroundedTotal.add(unrounded);
                }
            }
            entryRepository.save(entry);
            snapshot.add(entry);
        }

        if (amountPerUnit != null) {
            ca.setTotalAmount(total);
            ca.setRoundingResidual(unroundedTotal.subtract(total));
        }
        ca.setStatus(CorporateAction.Status.COMPUTED);
        // C6: fingerprint of what will be paid; (re)computing voids whatever was signed on earlier amounts.
        ca.setPayoutDigest(CorporateActionPayoutDigest.of(ca, snapshot));
        voidSignOffs(ca, "the entitlements were (re)computed at the record-date snapshot");
        repository.save(ca);
        log.info("Corporate action computed: id={} entries={} totalAmount={} roundingResidual={}", ca.getId(),
                resolution.positions().size(), ca.getTotalAmount(), ca.getRoundingResidual());
    }

    private TokenStandard resolveTokenStandard(UUID corporateActionId) {
        String standard = repository.findTokenStandardByCorpAction(corporateActionId);
        return standard != null ? TokenStandard.valueOf(standard) : null;
    }

    /**
     * Starts the payout of a COMPUTED action whose two sign-offs are valid. {@link FinalityGate}: the cron path must
     * {@code check}-and-skip, never {@code require} (an uncaught {@code FinalityNotReachedException} would fail this
     * action's transaction every day); a blocked action stays COMPUTED and is re-evaluated on the next run.
     *
     * <p>H6: eligible holders are paid individually. Every entitled holder goes through {@link PartyEligibilityGate}
     * (entity status, KYC incl. expiry, screening, Sperrvermerk); one that fails is HELD_BLOCKED (reason recorded,
     * audited, operator task) while all others proceed. Canton's {@code payCoupon}/{@code redeem} is a single
     * aggregate on-ledger call across all holders and cannot exclude one: there an ineligible holder holds the WHOLE
     * action back - visibly (settlement-hold reason, audited event, operator task) and without being escalated to
     * OVERDUE/DEFAULTED/MISSED - until the cause is resolved.
     */
    private void settle(CorporateAction ca) {
        TokenStandard standard = resolveTokenStandard(ca.getId());
        FinalityDecision decision = finalityGate.check(GatedOperation.CORPORATE_ACTION_SETTLEMENT_CONFIRM,
                ca.getAssetId(), standard, FinalityLevel.FINALIZED);
        if (decision instanceof FinalityDecision.Blocked blocked) {
            log.warn("Corporate action {} settlement held: {} (reason={})", ca.getId(), blocked.explanation(), blocked.reason());
            holdSettlement(ca, "finality: " + blocked.explanation());
            return;
        }
        log.info("Settling corporate action: id={} type={} assetId={}", ca.getId(), ca.getActionType(), ca.getAssetId());
        List<CorporateActionEntry> ineligible = ineligibleEntries(ca);
        boolean aggregate = standard != null && standard.name().startsWith("DAML");
        if (aggregate && !ineligible.isEmpty()) {
            holdSettlement(ca, "an on-ledger " + standard + " call pays every holder at once and cannot exclude "
                    + ineligible.size() + " ineligible holder(s): " + describe(ineligible));
            openPayoutHeldTasks(ca, ineligible);
            return;
        }
        if (!ineligible.isEmpty()) {
            markHeld(ca, ineligible);
        }
        ca.setSettlementHoldReason(null);
        ca.setStatus(CorporateAction.Status.AWAITING_SETTLEMENT);
        repository.save(ca);
        // Dispatch is handled by CorporateActionSettlementListener in the blockchain module,
        // which looks up the asset's token standard and calls the appropriate chain service.
        events.publishEvent(new CorporateActionSettlementRequestedEvent(ca.getId(), ca.getAssetId(), ca.getActionType()));
    }

    private void holdSettlement(CorporateAction ca, String reason) {
        boolean changed = !Objects.equals(reason, ca.getSettlementHoldReason());
        ca.setSettlementHoldReason(reason);
        repository.save(ca);
        if (changed) {
            events.publishEvent(new CorporateActionSettlementBlockedEvent(ca.getId(), reason));
        }
        log.warn("Corporate action {} settlement HELD by the system (stays COMPUTED, not escalated): {}", ca.getId(), reason);
    }

    /** Manual settlement path: hold the entries that are not eligible now; the rest is recorded as paid. */
    private void holdIneligibleEntries(CorporateAction ca) {
        List<CorporateActionEntry> ineligible = ineligibleEntries(ca);
        if (!ineligible.isEmpty()) {
            markHeld(ca, ineligible);
        }
    }

    /** PAYABLE entries with a non-zero entitlement whose holder fails the party-eligibility gate now. */
    private List<CorporateActionEntry> ineligibleEntries(CorporateAction ca) {
        List<CorporateActionEntry> result = new ArrayList<>();
        for (CorporateActionEntry entry : entryRepository.findByCorporateActionId(ca.getId())) {
            if (entry.getPayoutStatus() != CorporateActionEntry.PayoutStatus.PAYABLE
                    || entry.getEntitlementAmount() == null || entry.getEntitlementAmount().signum() == 0) {
                continue;
            }
            List<String> reasons = partyGate.check(entry.getInvestorId(), entry.getWalletAddress());
            if (!reasons.isEmpty()) {
                entry.setHeldReason("the entity " + String.join("; ", reasons));
                result.add(entry);
            }
        }
        return result;
    }

    private void markHeld(CorporateAction ca, List<CorporateActionEntry> ineligible) {
        List<Map<String, Object>> held = new ArrayList<>();
        for (CorporateActionEntry entry : ineligible) {
            entry.setPayoutStatus(CorporateActionEntry.PayoutStatus.HELD_BLOCKED);
            entryRepository.save(entry);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("assetHolderId", String.valueOf(entry.getAssetHolderId()));
            m.put("investorId", String.valueOf(entry.getInvestorId()));
            m.put("walletAddress", String.valueOf(entry.getWalletAddress()));
            m.put("entitlementAmount", entry.getEntitlementAmount().toPlainString());
            m.put("reason", entry.getHeldReason());
            held.add(m);
        }
        events.publishEvent(new CorporateActionPayoutHeldEvent(ca.getId(), ca.getAssetId(), held));
        openPayoutHeldTasks(ca, ineligible);
        log.warn("Corporate action {}: {} entitlement(s) HELD, the other holders are paid: {}",
                ca.getId(), ineligible.size(), held);
    }

    private void openPayoutHeldTasks(CorporateAction ca, List<CorporateActionEntry> entries) {
        for (CorporateActionEntry entry : entries) {
            if (entry.getInvestorId() != null) {
                entityTasks.open(entry.getInvestorId(), CorporateActionBlocks.TASK_PAYOUT_HELD,
                        ca.getId() + ":" + entry.getAssetHolderId(),
                        ca.getActionType() + " " + ca.getId() + ": entitlement " + entry.getEntitlementAmount()
                                + " " + ca.getCurrency() + " not paid - " + entry.getHeldReason(), null);
            }
        }
    }

    private static String describe(List<CorporateActionEntry> entries) {
        return entries.stream()
                .map(e -> "investor " + e.getInvestorId() + " (" + e.getHeldReason() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
