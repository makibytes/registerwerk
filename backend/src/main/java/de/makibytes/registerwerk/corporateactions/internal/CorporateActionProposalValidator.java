package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.web.dto.ProposeCorporateActionRequest;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates an issuer's {@link ProposeCorporateActionRequest} beyond what bean validation on the
 * record itself can express — per-type required fields already checked there
 * ({@code isRatioShapeValid}), plus the domain-context checks that need a repository lookup
 * (CALL against the bond's own terms). Only {@code DIVIDEND}/{@code SPLIT}/{@code CALL} are
 * accepted this round — every other {@link CorporateAction.ActionType} is a "not yet supported"
 * rejection, not a silent fallthrough, so an issuer gets a clear reason rather than a confusing
 * validation failure on unrelated fields.
 */
@Component
class CorporateActionProposalValidator {

    private static final Set<CorporateAction.ActionType> PROPOSABLE_TYPES =
            Set.of(CorporateAction.ActionType.DIVIDEND, CorporateAction.ActionType.SPLIT, CorporateAction.ActionType.CALL);

    private final AssetBondTermsRepository bondTermsRepository;

    CorporateActionProposalValidator(AssetBondTermsRepository bondTermsRepository) {
        this.bondTermsRepository = bondTermsRepository;
    }

    /** @return a fully-populated (but not yet persisted) {@link CorporateAction} ready for
     *          {@code CorporateActionService.propose} to save. */
    CorporateAction validateAndBuild(UUID assetId, ProposeCorporateActionRequest request) {
        if (!PROPOSABLE_TYPES.contains(request.actionType())) {
            throw new IllegalArgumentException(
                    "ActionType " + request.actionType() + " is not yet supported for issuer proposals "
                            + "(only DIVIDEND, SPLIT, and CALL are) — it remains a system-modelled type with no creation path.");
        }

        CorporateAction action = new CorporateAction();
        action.setAssetId(assetId);
        action.setActionType(request.actionType());
        action.setAnnouncementDate(request.announcementDate() != null ? request.announcementDate() : LocalDate.now());
        action.setNotes(request.notes());

        switch (request.actionType()) {
            case DIVIDEND -> validateAndApplyDividend(action, request);
            case SPLIT -> validateAndApplySplit(action, request);
            case CALL -> validateAndApplyCall(assetId, action, request);
            default -> throw new IllegalStateException("Unreachable — checked by PROPOSABLE_TYPES above");
        }
        return action;
    }

    private void validateAndApplyDividend(CorporateAction action, ProposeCorporateActionRequest request) {
        if (request.amountPerUnit() == null || request.amountPerUnit().signum() <= 0) {
            throw new IllegalArgumentException("DIVIDEND requires a positive amountPerUnit");
        }
        if (request.currency() == null || request.currency().isBlank()) {
            throw new IllegalArgumentException("DIVIDEND requires a currency");
        }
        if (request.recordDate() == null || request.paymentDate() == null) {
            throw new IllegalArgumentException("DIVIDEND requires both recordDate and paymentDate");
        }
        if (request.recordDate().isAfter(request.paymentDate())) {
            throw new IllegalArgumentException("recordDate must not be after paymentDate");
        }
        action.setAmountPerUnit(request.amountPerUnit());
        action.setCurrency(request.currency().trim().toUpperCase());
        action.setRecordDate(request.recordDate());
        action.setPaymentDate(request.paymentDate());
    }

    private void validateAndApplySplit(CorporateAction action, ProposeCorporateActionRequest request) {
        // ratioNumerator/ratioDenominator positivity and amount/currency exclusion are already
        // enforced by ProposeCorporateActionRequest.isRatioShapeValid() bean validation.
        if (request.recordDate() == null) {
            throw new IllegalArgumentException("SPLIT requires a recordDate");
        }
        action.setRatioNumerator(request.ratioNumerator());
        action.setRatioDenominator(request.ratioDenominator());
        action.setRecordDate(request.recordDate());
        action.setPaymentDate(request.recordDate());
    }

    private void validateAndApplyCall(UUID assetId, CorporateAction action, ProposeCorporateActionRequest request) {
        AssetBondTerms terms = bondTermsRepository.findById(assetId).orElse(null);
        if (terms == null || !terms.isCallable()) {
            throw new IllegalArgumentException("Asset " + assetId + " has no callable bond terms — CALL cannot be proposed");
        }

        LocalDate callDate;
        BigDecimal callPrice;
        if (request.callScheduleIndex() != null) {
            List<Map<String, Object>> schedule = terms.getCallSchedule();
            if (schedule == null || request.callScheduleIndex() >= schedule.size()) {
                throw new IllegalArgumentException("callScheduleIndex " + request.callScheduleIndex()
                        + " is out of range for this bond's call schedule");
            }
            // Never trust client-supplied date/price for a scheduled call — resolve from the
            // bond's own terms, which only an operator (via BondTermsController) can set.
            Map<String, Object> entry = schedule.get(request.callScheduleIndex());
            callDate = LocalDate.parse(String.valueOf(entry.get("callDate")));
            callPrice = new BigDecimal(String.valueOf(entry.get("callPrice")));
        } else {
            if (request.paymentDate() == null || request.amountPerUnit() == null || request.amountPerUnit().signum() <= 0) {
                throw new IllegalArgumentException(
                        "A CALL not referencing a scheduled callScheduleIndex requires both paymentDate (call date) "
                                + "and a positive amountPerUnit (call price)");
            }
            callDate = request.paymentDate();
            callPrice = request.amountPerUnit();
        }

        if (callDate.isBefore(terms.getIssueDate()) || callDate.isAfter(terms.getMaturityDate())) {
            throw new IllegalArgumentException("Call date " + callDate + " is outside the bond's issue/maturity window");
        }

        action.setRecordDate(callDate);
        action.setPaymentDate(callDate);
        action.setAmountPerUnit(callPrice);
        action.setCurrency(terms.getCurrencyIso());
    }
}
