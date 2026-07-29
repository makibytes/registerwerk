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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BondMaturityJob unit tests — closes the MATURED/DEFAULTED dead ends")
class BondMaturityJobTest {

    @Mock private AssetBondTermsRepository bondTermsRepository;
    @Mock private CorporateActionRepository corporateActionRepository;
    @Mock private CorporateActionService corporateActionService;
    @Mock private AssetRepository assetRepository;

    @InjectMocks
    private BondMaturityJob job;

    private static AssetBondTerms terms(UUID assetId, BondStatus status, LocalDate maturity) {
        AssetBondTerms t = new AssetBondTerms();
        t.setAssetId(assetId);
        t.setBondStatus(status);
        t.setMaturityDate(maturity);
        t.setFaceValue(new BigDecimal("1000"));
        t.setCurrencyIso("EUR");
        return t;
    }

    @Test
    @DisplayName("a bond past its maturity date transitions to MATURED and raises a REDEMPTION action")
    void maturedBond_transitionsAndRaisesRedemption() {
        UUID assetId = UUID.randomUUID();
        AssetBondTerms t = terms(assetId, BondStatus.ACTIVE, LocalDate.now().minusDays(1));

        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(t));
        when(corporateActionRepository.existsActiveRedemptionForAsset(assetId)).thenReturn(false);
        when(bondTermsRepository.findByBondStatus(BondStatus.MATURED)).thenReturn(List.of());

        job.processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
        verify(bondTermsRepository, times(1)).save(t);

        ArgumentCaptor<CorporateAction> captor = ArgumentCaptor.forClass(CorporateAction.class);
        verify(corporateActionService).announce(captor.capture());
        assertThat(captor.getValue().getActionType()).isEqualTo(CorporateAction.ActionType.REDEMPTION);
        assertThat(captor.getValue().getAmountPerUnit()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a matured bond whose register was transferred to a successor operator does not raise a redemption action")
    void maturedBond_skipsRedemptionActionWhenTransferredOut() {
        UUID assetId = UUID.randomUUID();
        AssetBondTerms t = terms(assetId, BondStatus.ACTIVE, LocalDate.now().minusDays(1));
        Asset transferredAsset = new Asset();
        transferredAsset.setStatus(AssetStatus.TRANSFERRED_OUT);

        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(t));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(transferredAsset));
        when(bondTermsRepository.findByBondStatus(BondStatus.MATURED)).thenReturn(List.of());

        job.processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
        verify(corporateActionRepository, never()).existsActiveRedemptionForAsset(any());
        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("does not raise a second redemption action if one is already active")
    void maturedBond_doesNotDuplicateRedemption() {
        UUID assetId = UUID.randomUUID();
        AssetBondTerms t = terms(assetId, BondStatus.ACTIVE, LocalDate.now());

        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of(t));
        when(corporateActionRepository.existsActiveRedemptionForAsset(assetId)).thenReturn(true);
        when(bondTermsRepository.findByBondStatus(BondStatus.MATURED)).thenReturn(List.of());

        job.processMaturitiesAndDefaults();

        verify(corporateActionService, never()).announce(any());
    }

    @Test
    @DisplayName("a MATURED bond with an overdue unsettled redemption transitions to DEFAULTED")
    void overdueRedemption_transitionsToDefaulted() {
        UUID assetId = UUID.randomUUID();
        AssetBondTerms t = terms(assetId, BondStatus.MATURED, LocalDate.now().minusDays(30));
        CorporateAction overdueAction = new CorporateAction();

        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of());
        when(bondTermsRepository.findByBondStatus(BondStatus.MATURED)).thenReturn(List.of(t));
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of(overdueAction));

        job.processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.DEFAULTED);
        verify(bondTermsRepository).save(t);
    }

    @Test
    @DisplayName("a MATURED bond with no overdue redemption stays MATURED")
    void maturedBond_withoutOverdueRedemption_staysMatured() {
        UUID assetId = UUID.randomUUID();
        AssetBondTerms t = terms(assetId, BondStatus.MATURED, LocalDate.now().minusDays(2));

        when(bondTermsRepository.findMaturedButNotTransitioned(any())).thenReturn(List.of());
        when(bondTermsRepository.findByBondStatus(BondStatus.MATURED)).thenReturn(List.of(t));
        when(corporateActionRepository.findOverdueRedemptions(any(), any())).thenReturn(List.of());

        job.processMaturitiesAndDefaults();

        assertThat(t.getBondStatus()).isEqualTo(BondStatus.MATURED);
        verify(bondTermsRepository, never()).save(any());
    }
}
