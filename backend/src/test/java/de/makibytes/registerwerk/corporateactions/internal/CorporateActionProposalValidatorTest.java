package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.web.dto.ProposeCorporateActionRequest;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Covers per-type field validation for issuer proposals — only DIVIDEND/SPLIT/CALL are
 * proposable; CALL's callScheduleIndex resolution is the trickiest path, since it must never
 * trust client-supplied date/price for a scheduled call (only an operator, via BondTermsController,
 * can set the schedule those indices resolve against).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorporateActionProposalValidator unit tests")
class CorporateActionProposalValidatorTest {

    @Mock private AssetBondTermsRepository bondTermsRepository;

    private CorporateActionProposalValidator validator;

    private final UUID assetId = UUID.randomUUID();

    private CorporateActionProposalValidatorTest init() {
        validator = new CorporateActionProposalValidator(bondTermsRepository);
        return this;
    }

    private static ProposeCorporateActionRequest dividendRequest(BigDecimal amountPerUnit, String currency,
                                                                  LocalDate recordDate, LocalDate paymentDate) {
        return new ProposeCorporateActionRequest(CorporateAction.ActionType.DIVIDEND, null, recordDate, paymentDate,
                amountPerUnit, currency, null, null, null, null);
    }

    private static ProposeCorporateActionRequest splitRequest(BigDecimal numerator, BigDecimal denominator, LocalDate recordDate) {
        return new ProposeCorporateActionRequest(CorporateAction.ActionType.SPLIT, null, recordDate, null,
                null, null, numerator, denominator, null, null);
    }

    private static ProposeCorporateActionRequest callRequestByIndex(int index) {
        return new ProposeCorporateActionRequest(CorporateAction.ActionType.CALL, null, null, null,
                null, null, null, null, index, null);
    }

    private static ProposeCorporateActionRequest callRequestByCustomDate(LocalDate callDate, BigDecimal callPrice) {
        return new ProposeCorporateActionRequest(CorporateAction.ActionType.CALL, null, null, callDate,
                callPrice, null, null, null, null, null);
    }

    private AssetBondTerms callableBondTerms(LocalDate issueDate, LocalDate maturityDate) {
        AssetBondTerms terms = new AssetBondTerms();
        terms.setAssetId(assetId);
        terms.setCallable(true);
        terms.setIssueDate(issueDate);
        terms.setMaturityDate(maturityDate);
        terms.setCurrencyIso("EUR");
        return terms;
    }

    @Test
    @DisplayName("rejects every ActionType other than DIVIDEND/SPLIT/CALL")
    void rejectsUnsupportedActionTypes() {
        init();
        for (CorporateAction.ActionType type : List.of(CorporateAction.ActionType.COUPON,
                CorporateAction.ActionType.REVERSE_SPLIT, CorporateAction.ActionType.CONVERSION,
                CorporateAction.ActionType.REDEMPTION, CorporateAction.ActionType.PARTIAL_REDEMPTION,
                CorporateAction.ActionType.CAPITAL_CALL, CorporateAction.ActionType.INTEREST_PAYMENT)) {
            ProposeCorporateActionRequest request = new ProposeCorporateActionRequest(type, null, null, null,
                    null, null, null, null, null, null);
            assertThatThrownBy(() -> validator.validateAndBuild(assetId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not yet supported");
        }
    }

    @Test
    @DisplayName("DIVIDEND builds a fully-populated action from a valid request")
    void dividend_buildsValidAction() {
        init();
        LocalDate recordDate = LocalDate.now().plusDays(10);
        LocalDate paymentDate = LocalDate.now().plusDays(20);
        ProposeCorporateActionRequest request = dividendRequest(new BigDecimal("1.50"), "eur", recordDate, paymentDate);

        CorporateAction action = validator.validateAndBuild(assetId, request);

        assertThat(action.getAssetId()).isEqualTo(assetId);
        assertThat(action.getActionType()).isEqualTo(CorporateAction.ActionType.DIVIDEND);
        assertThat(action.getAmountPerUnit()).isEqualByComparingTo("1.50");
        assertThat(action.getCurrency()).isEqualTo("EUR");
        assertThat(action.getRecordDate()).isEqualTo(recordDate);
        assertThat(action.getPaymentDate()).isEqualTo(paymentDate);
    }

    @Test
    @DisplayName("DIVIDEND rejects a recordDate after paymentDate")
    void dividend_rejectsRecordDateAfterPaymentDate() {
        init();
        LocalDate paymentDate = LocalDate.now().plusDays(5);
        LocalDate recordDate = paymentDate.plusDays(1);
        ProposeCorporateActionRequest request = dividendRequest(BigDecimal.ONE, "EUR", recordDate, paymentDate);

        assertThatThrownBy(() -> validator.validateAndBuild(assetId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordDate must not be after paymentDate");
    }

    @Test
    @DisplayName("DIVIDEND rejects a non-positive amountPerUnit")
    void dividend_rejectsNonPositiveAmount() {
        init();
        ProposeCorporateActionRequest request = dividendRequest(BigDecimal.ZERO, "EUR",
                LocalDate.now(), LocalDate.now().plusDays(1));

        assertThatThrownBy(() -> validator.validateAndBuild(assetId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountPerUnit");
    }

    @Test
    @DisplayName("SPLIT builds an action carrying the ratio, with paymentDate defaulted to recordDate")
    void split_buildsValidAction() {
        init();
        LocalDate recordDate = LocalDate.now().plusDays(3);
        ProposeCorporateActionRequest request = splitRequest(new BigDecimal("2"), new BigDecimal("1"), recordDate);

        CorporateAction action = validator.validateAndBuild(assetId, request);

        assertThat(action.getRatioNumerator()).isEqualByComparingTo("2");
        assertThat(action.getRatioDenominator()).isEqualByComparingTo("1");
        assertThat(action.getRecordDate()).isEqualTo(recordDate);
        assertThat(action.getPaymentDate()).isEqualTo(recordDate);
    }

    @Test
    @DisplayName("SPLIT rejects a missing recordDate")
    void split_rejectsMissingRecordDate() {
        init();
        ProposeCorporateActionRequest request = splitRequest(new BigDecimal("2"), new BigDecimal("1"), null);

        assertThatThrownBy(() -> validator.validateAndBuild(assetId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordDate");
    }

    @Test
    @DisplayName("CALL by callScheduleIndex resolves callDate/callPrice from the bond's own terms, never the request's")
    void call_byScheduleIndex_resolvesFromBondTerms() {
        init();
        AssetBondTerms terms = callableBondTerms(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1));
        terms.setCallSchedule(List.of(
                Map.of("callDate", "2025-06-01", "callPrice", new BigDecimal("101.5")),
                Map.of("callDate", "2027-06-01", "callPrice", new BigDecimal("100.0"))));
        when(bondTermsRepository.findById(assetId)).thenReturn(Optional.of(terms));

        CorporateAction action = validator.validateAndBuild(assetId, callRequestByIndex(0));

        assertThat(action.getPaymentDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(action.getRecordDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(action.getAmountPerUnit()).isEqualByComparingTo("101.5");
        assertThat(action.getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("CALL by callScheduleIndex=0 (the first scheduled call) is accepted — a 0-based index must not be rejected as non-positive")
    void call_byScheduleIndexZero_isAccepted() {
        init();
        AssetBondTerms terms = callableBondTerms(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1));
        terms.setCallSchedule(List.of(Map.of("callDate", "2025-06-01", "callPrice", new BigDecimal("101.5"))));
        when(bondTermsRepository.findById(assetId)).thenReturn(Optional.of(terms));

        CorporateAction action = validator.validateAndBuild(assetId, callRequestByIndex(0));

        assertThat(action.getPaymentDate()).isEqualTo(LocalDate.of(2025, 6, 1));
    }

    @Test
    @DisplayName("CALL by callScheduleIndex rejects an out-of-range index")
    void call_byScheduleIndex_rejectsOutOfRange() {
        init();
        AssetBondTerms terms = callableBondTerms(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1));
        terms.setCallSchedule(List.of(Map.of("callDate", "2025-06-01", "callPrice", new BigDecimal("101.5"))));
        when(bondTermsRepository.findById(assetId)).thenReturn(Optional.of(terms));

        assertThatThrownBy(() -> validator.validateAndBuild(assetId, callRequestByIndex(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    @DisplayName("CALL without a callScheduleIndex accepts a custom paymentDate/amountPerUnit within the bond's window")
    void call_withCustomDate_isAccepted() {
        init();
        AssetBondTerms terms = callableBondTerms(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1));
        when(bondTermsRepository.findById(assetId)).thenReturn(Optional.of(terms));

        CorporateAction action = validator.validateAndBuild(assetId,
                callRequestByCustomDate(LocalDate.of(2026, 1, 1), new BigDecimal("102.0")));

        assertThat(action.getPaymentDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(action.getAmountPerUnit()).isEqualByComparingTo("102.0");
    }

    @Test
    @DisplayName("CALL rejects a call date outside the bond's issue/maturity window")
    void call_rejectsDateOutsideWindow() {
        init();
        AssetBondTerms terms = callableBondTerms(LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1));
        when(bondTermsRepository.findById(assetId)).thenReturn(Optional.of(terms));

        assertThatThrownBy(() -> validator.validateAndBuild(assetId,
                callRequestByCustomDate(LocalDate.of(2030, 1, 1), new BigDecimal("100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the bond's issue/maturity window");
    }

    @Test
    @DisplayName("CALL rejects an asset with no bond terms at all")
    void call_rejectsMissingBondTerms() {
        init();
        when(bondTermsRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validator.validateAndBuild(assetId, callRequestByCustomDate(LocalDate.now(), BigDecimal.TEN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no callable bond terms");
    }

    @Test
    @DisplayName("CALL rejects a non-callable bond")
    void call_rejectsNonCallableBond() {
        init();
        AssetBondTerms terms = callableBondTerms(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1));
        terms.setCallable(false);
        when(bondTermsRepository.findById(assetId)).thenReturn(Optional.of(terms));

        assertThatThrownBy(() -> validator.validateAndBuild(assetId, callRequestByCustomDate(LocalDate.of(2026, 1, 1), BigDecimal.TEN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no callable bond terms");
    }
}
