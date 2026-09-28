package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3-05. Bond under test matures Mon 30 Jun 2025 (TARGET2 business day): payment 30 Jun, record
 * Fri 27 Jun (offset 1), announcement Fri 20 Jun (lead 5 business days).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BondMaturityJob unit tests")
class BondMaturityJobTest {

    private static final LocalDate MATURITY = LocalDate.of(2025, 6, 30);

    @Mock private AssetBondTermsRepository bondTermsRepository;
    @Mock private CorporateActionRepository corporateActionRepository;
    @Mock private CorporateActionService corporateActionService;
    @Mock private AssetRepository assetRepository;

    private BondMaturityJob jobOn(LocalDate today) {
        return new BondMaturityJob(bondTermsRepository, corporateActionRepository, corporateActionService,
                assetRepository, CorporateActionTestSupport.registerClockAt(today));
    }

    private AssetBondTerms terms(BondStatus status) {
        AssetBondTerms t = new AssetBondTerms();
        t.setAssetId(UUID.randomUUID());
        t.setBondStatus(status);
        t.setMaturityDate(MATURITY);
        t.setFaceValue(new BigDecimal("1000"));
        t.setCurrencyIso("EUR");
        when(bondTermsRepository.findByBondStatus(status)).thenReturn(List.of(t));
        return t;
    }

    private static CorporateAction unsettledRedemption(LocalDate paymentDate) {
        CorporateAction ca = new CorporateAction();
        ca.setPaymentDate(paymentDate);
        return ca;
    }

    @Test
    @DisplayName("redemption is raised at its announcement date with record/payment dates from the conventions")
    void raisesAtAnnouncementDate() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);

        jobOn(LocalDate.of(2025, 6, 19)).processMaturitiesAndDefaults();
        verify(corporateActionService, never()).announce(any());

        jobOn(LocalDate.of(2025, 6, 20)).processMaturitiesAndDefaults();
        ArgumentCaptor<CorporateAction> captor = ArgumentCaptor.forClass(CorporateAction.class);
        verify(corporateActionService).announce(captor.capture());
        CorporateAction ca = captor.getValue();
        assertThat(ca.getActionType()).isEqualTo(CorporateAction.ActionType.REDEMPTION);
        assertThat(ca.getAnnouncementDate()).isEqualTo(LocalDate.of(2025, 6, 20));
        assertThat(ca.getRecordDate()).isEqualTo(LocalDate.of(2025, 6, 27));
        assertThat(ca.getPaymentDate()).isEqualTo(MATURITY);
        assertThat(ca.getAmountPerUnit()).isEqualByComparingTo("1000");
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.ACTIVE);
    }

    @Test
    @DisplayName("a bond past its maturity date transitions to MATURED")
    void maturityDateTransitionsToMatured() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(t));

        jobOn(MATURITY).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
    }

    @Test
    @DisplayName("a redemption settled on the payment date is never flagged OVERDUE/DEFAULTED")
    void settledOnPaymentDateIsNeverDefaulted() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        // Settled → no row from findOverdueRedemptions.
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of());

        jobOn(MATURITY.plusDays(1)).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("an unsettled redemption is OVERDUE inside the principal grace period and DEFAULTED only after it")
    void defaultedOnlyAfterGrace() {
        AssetBondTerms t = terms(BondStatus.MATURED);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);
        when(corporateActionRepository.findOverdueRedemptions(any(), any()))
                .thenReturn(List.of(unsettledRedemption(MATURITY)));

        jobOn(MATURITY.plusDays(1)).processMaturitiesAndDefaults();
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.OVERDUE);

        // Default grace is 7 days: still OVERDUE on day 7, DEFAULTED on day 8.
        jobOn(MATURITY.plusDays(7)).processMaturitiesAndDefaults();
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.OVERDUE);

        when(bondTermsRepository.findByBondStatus(BondStatus.OVERDUE)).thenReturn(List.of(t));
        jobOn(MATURITY.plusDays(8)).processMaturitiesAndDefaults();
        assertThat(t.getBondStatus()).isEqualTo(BondStatus.DEFAULTED);
    }

    @Test
    @DisplayName("a bond whose CALL settled is CALLED at maturity and gets no second redemption")
    void callSettledSetsCalledAndSkipsMaturityRedemption() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(t));
        when(corporateActionRepository.existsSettledCallForAsset(t.getAssetId())).thenReturn(true);

        jobOn(MATURITY).processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.CALLED);
        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("a called bond that is not yet past maturity gets no redemption at its announcement date either")
    void calledBondSkipsRedemptionBeforeMaturity() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(corporateActionRepository.existsSettledCallForAsset(t.getAssetId())).thenReturn(true);

        jobOn(LocalDate.of(2025, 6, 24)).processMaturitiesAndDefaults();

        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("a register transferred to a successor operator raises no redemption")
    void transferredOutRaisesNothing() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        Asset transferred = new Asset();
        transferred.setStatus(AssetStatus.TRANSFERRED_OUT);
        when(assetRepository.findById(t.getAssetId())).thenReturn(Optional.of(transferred));

        jobOn(LocalDate.of(2025, 6, 24)).processMaturitiesAndDefaults();

        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("does not raise a second redemption action if one is already active")
    void doesNotDuplicateRedemption() {
        AssetBondTerms t = terms(BondStatus.ACTIVE);
        when(corporateActionRepository.existsActiveRedemptionForAsset(t.getAssetId())).thenReturn(true);

        jobOn(LocalDate.of(2025, 6, 24)).processMaturitiesAndDefaults();

        verify(corporateActionService, never()).announce(any());
    }
}
