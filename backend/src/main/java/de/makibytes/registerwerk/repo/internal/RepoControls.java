package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.repo.api.RepoDeskParticipantRepository;
import de.makibytes.registerwerk.repo.api.RepoSubstitutionRequestRepository;
import de.makibytes.registerwerk.repo.api.RepoTradeRepository;
import de.makibytes.registerwerk.repo.api.RepoTypes;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.trading.api.ListingStatus;
import de.makibytes.registerwerk.trading.api.SettlementStatus;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Party gates, holdings / double-pledge checks and collateral lifecycle rules shared by the RFQ and
 * trade services (5A-08, 5A-09). Everything here fails closed.
 */
@Component
class RepoControls {
    private static final Pattern LEI = Pattern.compile("[A-Z0-9]{18}[0-9]{2}");
    private static final Set<CorporateAction.ActionType> TERMINATING = EnumSet.of(
            CorporateAction.ActionType.CALL, CorporateAction.ActionType.REDEMPTION);
    private static final Set<CorporateAction.Status> LIVE_ACTION = EnumSet.of(
            CorporateAction.Status.ANNOUNCED, CorporateAction.Status.SNAPSHOT_BLOCKED,
            CorporateAction.Status.RECORD_DATE_SET, CorporateAction.Status.COMPUTED,
            CorporateAction.Status.AWAITING_SETTLEMENT);

    private final PartyEligibilityGate gate;
    private final LegalEntityRepository entities;
    private final RepoDeskParticipantRepository participants;
    private final AssetHolderRepository holders;
    private final TradeListingRepository listings;
    private final TradeExecutionRepository executions;
    private final RepoTradeRepository trades;
    private final RepoSubstitutionRequestRepository substitutions;
    private final AssetBondTermsRepository bondTerms;
    private final CorporateActionRepository corporateActions;
    private final EntityTaskPort entityTasks;

    RepoControls(PartyEligibilityGate gate, LegalEntityRepository entities, RepoDeskParticipantRepository participants,
                 AssetHolderRepository holders, TradeListingRepository listings, TradeExecutionRepository executions,
                 RepoTradeRepository trades, RepoSubstitutionRequestRepository substitutions,
                 AssetBondTermsRepository bondTerms, CorporateActionRepository corporateActions,
                 EntityTaskPort entityTasks) {
        this.gate = gate; this.entities = entities; this.participants = participants; this.holders = holders;
        this.listings = listings; this.executions = executions; this.trades = trades;
        this.substitutions = substitutions; this.bondTerms = bondTerms; this.corporateActions = corporateActions;
        this.entityTasks = entityTasks;
    }

    /** Active opt-in, professional / eligible-counterparty category, KYC/screening gate (T5-07, 5C-03). */
    void requireParticipant(UUID entityId, String purpose) {
        LegalEntity entity = entities.findById(entityId)
                .orElseThrow(() -> new AccessDeniedException("Company context not found"));
        if (entity.getStatus() != EntityStatus.ACTIVE) {
            throw new AccessDeniedException("Company must be active to use the Repo Desk");
        }
        if (!participants.findById(entityId).filter(p -> p.isActive()).isPresent()) {
            throw new AccessDeniedException("Company has not opted in to the Repo Desk");
        }
        requireEligible(entity, purpose);
    }

    /** Same tests for a company that is not the caller (target selection, quoting counterparty). */
    void requireParticipantCounterparty(UUID entityId, String purpose) {
        try {
            requireParticipant(entityId, purpose);
        } catch (AccessDeniedException | ComplianceGateException e) {
            throw new IllegalArgumentException("Counterparty " + entityId + " cannot take part in the Repo Desk");
        }
    }

    /** Eligibility without the opt-in (used for opt-in itself and for PARTY_FLAGGED detection). */
    List<String> eligibilityReasons(UUID entityId) {
        LegalEntity entity = entities.findById(entityId).orElse(null);
        if (entity == null) return List.of("is unknown");
        List<String> reasons = new java.util.ArrayList<>();
        categoryReason(entity, reasons);
        reasons.addAll(gate.check(entityId, null));
        return reasons;
    }

    void requireEligible(LegalEntity entity, String purpose) {
        List<String> reasons = new java.util.ArrayList<>();
        categoryReason(entity, reasons);
        if (!reasons.isEmpty()) {
            throw new ComplianceGateException("Entity " + entity.getId() + " is not eligible for " + purpose
                    + ": " + String.join("; ", reasons) + ".");
        }
        gate.require(entity.getId(), null, purpose);
    }

    /**
     * 9A-08: the gate for a creditor's PROTECTIVE actions on an existing trade (margin call, default notice, default
     * declaration). Unlike {@link #requireEligible} (used when NEW exposure is opened) it refuses only on HARD stops: an
     * entity that is not ACTIVE, or an unresolved sanctions-screening result. Everything else (expired / unapproved KYC,
     * a Sperrvermerk on any wallet, client category) is returned as a SOFT reason: the action is allowed, the caller
     * flags the trade and {@link #openEnforcementTask} hands it to the operator. The debtor keeps its cure rights
     * regardless, so disarming only the creditor was an asymmetry nobody decided.
     *
     * @return the soft reasons (empty = fully eligible)
     */
    List<String> requireProtectiveActor(LegalEntity entity, String purpose) {
        List<String> hard = gate.hardStops(entity.getId());
        if (!hard.isEmpty()) {
            throw new ComplianceGateException("Entity " + entity.getId() + " cannot perform " + purpose + ": "
                    + String.join("; ", hard) + ".");
        }
        return eligibilityReasons(entity.getId());
    }

    /** Operator task on the enforcing entity for a protective action taken while soft-ineligible (9A-08). */
    void openEnforcementTask(UUID entityId, UUID tradeId, String purpose, List<String> softReasons, UUID actorId) {
        entityTasks.open(entityId, "REPO_PARTY_INELIGIBLE_ENFORCEMENT", tradeId.toString(),
                "Repo trade " + tradeId + ": " + purpose + " was taken although the entity "
                        + String.join("; ", softReasons) + ". Review the party status.", actorId);
    }

    private void categoryReason(LegalEntity entity, List<String> reasons) {
        if (entity.getType() == EntityType.AUDITOR) {
            reasons.add("auditor entities cannot use the repo desk");
        }
        ClientCategory category = entity.getClientCategory();
        if (category != ClientCategory.PROFESSIONAL && category != ClientCategory.ELIGIBLE_COUNTERPARTY) {
            reasons.add("client category must be PROFESSIONAL or ELIGIBLE_COUNTERPARTY");
        }
    }

    /** SFTR record keeping (T5-11): both parties need a well-formed LEI before terms are fixed. */
    void requireLei(LegalEntity entity) {
        String lei = entity.getLeiCode();
        if (lei == null || !LEI.matcher(lei.trim().toUpperCase()).matches()) {
            throw new IllegalArgumentException("Company " + entity.getCurrentName()
                    + " needs a valid 20-character LEI before it can enter a repo");
        }
    }

    /** Units of the asset the entity holds on the register and has not committed elsewhere. */
    BigDecimal availableHolding(UUID entityId, UUID assetId) {
        BigDecimal held = holders.sumActiveNominalByInvestorIdAndAssetId(entityId, assetId);
        BigDecimal committed = BigDecimal.ZERO;
        for (AssetHolder holder : holders.findActiveByInvestorId(entityId)) {
            if (!assetId.equals(holder.getAssetId())) continue;
            committed = committed
                    .add(listings.sumQuantityAvailableBySellerHolderIdAndStatusIn(holder.getId(),
                            List.of(ListingStatus.OPEN, ListingStatus.PARTIALLY_FILLED)))
                    .add(executions.sumExecutedQuantityBySellerHolderIdAndSettlementStatusIn(holder.getId(),
                            SettlementStatus.RESERVING));
        }
        return held.subtract(committed).subtract(pledged(entityId, assetId)).max(BigDecimal.ZERO);
    }

    BigDecimal pledged(UUID entityId, UUID assetId) {
        return trades.sumPledged(entityId, assetId, RepoTypes.TradeStatus.OPEN_STATES)
                .add(substitutions.sumApprovedReplacement(entityId, assetId));
    }

    /**
     * H11: takes the {@code (entity, asset)} advisory lock the trading module also takes (listing, reservation,
     * settlement) before reading the committed quantities, so two RFQs of one borrower, or a pledge racing a
     * trade confirmation, cannot both pass. The lock is transaction-scoped and held until the caller commits.
     */
    void requireHolding(UUID borrowerEntityId, UUID assetId, BigDecimal quantity) {
        executions.lockHolding(borrowerEntityId, assetId);
        if (availableHolding(borrowerEntityId, assetId).compareTo(quantity) < 0) {
            throw new ComplianceGateException("The cash borrower does not hold " + quantity.stripTrailingZeros().toPlainString()
                    + " unencumbered units of the collateral on the register");
        }
    }

    /** 5A-08: the term cannot outlive the collateral (maturity, call, redemption). */
    void requireTermWithinCollateralLife(UUID assetId, LocalDate endDate) {
        AssetBondTerms terms = bondTerms.findById(assetId).orElse(null);
        if (terms == null) return;
        if (terms.getBondStatus() != BondStatus.ACTIVE) {
            throw new IllegalArgumentException("Collateral bond is " + terms.getBondStatus() + " and cannot back a repo");
        }
        if (!endDate.isBefore(terms.getMaturityDate())) {
            throw new IllegalArgumentException("Repo end date must be before the collateral maturity date "
                    + terms.getMaturityDate());
        }
        for (CorporateAction action : corporateActions.findByAssetId(assetId)) {
            if (!TERMINATING.contains(action.getActionType()) || !LIVE_ACTION.contains(action.getStatus())) continue;
            LocalDate cutoff = action.getPaymentDate() != null ? action.getPaymentDate() : action.getRecordDate();
            if (cutoff == null || !endDate.isBefore(cutoff)) {
                throw new IllegalArgumentException("Repo end date must be before the pending "
                        + action.getActionType() + " of the collateral");
            }
        }
    }
}
