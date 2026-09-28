package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.EntryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T3-13 (register-entry edits against a recorded instruction) and the T3-08 credit paths of HolderService. */
class HolderServiceInstructionTest {

    private static final String WALLET = "0x" + "a".repeat(40);
    private static final HolderInstruction INS = new HolderInstruction(InstructingParty.COURT, "AZ 12 C 345/26");

    private final AssetHolderRepository holders = mock(AssetHolderRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
    private final HolderChangeRepository changes = mock(HolderChangeRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final HolderService service = new HolderService(holders, assets, events, entities, deployments, changes);

    private final UUID assetId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();
    private Asset asset;

    @BeforeEach
    void setUp() {
        asset = new Asset();
        asset.setId(assetId);
        asset.setStatus(AssetStatus.ISSUED);
        asset.setEntryType(EntryType.COLLECTIVE);
        when(assets.findById(assetId)).thenReturn(Optional.of(asset));
        when(holders.save(any())).thenAnswer(inv -> {
            AssetHolder h = inv.getArgument(0);
            if (h.getId() == null) h.setId(UUID.randomUUID());
            return h;
        });
        when(deployments.findByAssetId(assetId)).thenReturn(List.of());
    }

    private LegalEntity investor(KycStatus kyc) {
        LegalEntity e = new LegalEntity();
        e.setId(investorId);
        e.setKycStatus(kyc);
        return e;
    }

    @Test
    void unknownInvestorRejected() {
        when(entities.findById(investorId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addHolder(assetId, investorId, WALLET, BigDecimal.ZERO, INS, null, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown investor");
        verify(holders, never()).save(any());
    }

    @Test
    void investorWithoutApprovedKycRejected() {
        when(entities.findById(investorId)).thenReturn(Optional.of(investor(KycStatus.IN_PROGRESS)));
        assertThatThrownBy(() -> service.addHolder(assetId, investorId, WALLET, BigDecimal.ZERO, INS, null, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KYC");
    }

    @Test
    void manualEntryOnDeployedAssetMustBeZero() {
        when(entities.findById(investorId)).thenReturn(Optional.of(investor(KycStatus.APPROVED)));
        AssetDeployment dep = new AssetDeployment();
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        when(deployments.findByAssetId(assetId)).thenReturn(List.of(dep));

        assertThatThrownBy(() -> service.addHolder(assetId, investorId, WALLET, new BigDecimal("5"), INS, null, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nominalAmount 0");
        // a wallet mapping (nominal 0) is fine
        AssetHolder mapped = service.addHolder(assetId, investorId, WALLET, BigDecimal.ZERO, INS, null, "REGISTRY_ADMIN");
        assertThat(mapped.getNominalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void instructionIsRequiredAndRecorded() {
        when(entities.findById(investorId)).thenReturn(Optional.of(investor(KycStatus.APPROVED)));
        assertThatThrownBy(() -> service.addHolder(assetId, investorId, WALLET, BigDecimal.ZERO,
                new HolderInstruction(InstructingParty.HOLDER, " "), null, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);

        service.addHolder(assetId, investorId, WALLET, BigDecimal.ZERO, INS, null, "REGISTRY_ADMIN");
        ArgumentCaptor<HolderChange> captor = ArgumentCaptor.forClass(HolderChange.class);
        verify(changes).save(captor.capture());
        assertThat(captor.getValue().getInstructingParty()).isEqualTo(InstructingParty.COURT);
        assertThat(captor.getValue().getInstructionReference()).isEqualTo("AZ 12 C 345/26");
        assertThat(captor.getValue().getChangeType()).isEqualTo(HolderChange.ChangeType.CREATED);
    }

    private AssetHolder singleEntryHolder() {
        AssetHolder h = new AssetHolder();
        h.setId(UUID.randomUUID());
        h.setAssetId(assetId);
        h.setEntryType(EntryType.INDIVIDUAL);
        h.setNominalAmount(BigDecimal.TEN);
        h.setThirdPartyRights("Nießbrauch");
        h.setDisposalRestrictions("Verfügungsbeschränkung");
        when(holders.findByIdAndAssetId(h.getId(), assetId)).thenReturn(Optional.of(h));
        return h;
    }

    @Test
    void emptyStringDoesNotClearRights() {
        AssetHolder h = singleEntryHolder();
        // blank text = no change; only the consumer flag actually changes
        service.updateSingleEntryAttributes(assetId, h.getId(),
                new HolderService.AttributeChange(true, "", "  ", null, false, false), INS, null, "REGISTRY_ADMIN");
        assertThat(h.getThirdPartyRights()).isEqualTo("Nießbrauch");
        assertThat(h.getDisposalRestrictions()).isEqualTo("Verfügungsbeschränkung");
        // a request that changes nothing is refused rather than silently accepted
        assertThatThrownBy(() -> service.updateSingleEntryAttributes(assetId, h.getId(),
                new HolderService.AttributeChange(null, "", "", "", false, false), INS, null, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changes nothing");
    }

    @Test
    void clearingRightsNeedsExplicitFlagAndRecordsBeforeAfter() {
        AssetHolder h = singleEntryHolder();
        service.updateSingleEntryAttributes(assetId, h.getId(),
                new HolderService.AttributeChange(null, null, null, null, true, false), INS, null, "REGISTRY_ADMIN");
        assertThat(h.getThirdPartyRights()).isNull();
        assertThat(h.getDisposalRestrictions()).isEqualTo("Verfügungsbeschränkung");
        ArgumentCaptor<HolderChange> captor = ArgumentCaptor.forClass(HolderChange.class);
        verify(changes).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo(HolderChange.ChangeType.RIGHTS_CHANGED);
        assertThat(captor.getValue().getBeforeState()).containsEntry("thirdPartyRights", "Nießbrauch");
        assertThat(captor.getValue().getAfterState()).containsEntry("thirdPartyRights", null);
    }

    @Test
    void valueAndClearFlagAreMutuallyExclusive() {
        AssetHolder h = singleEntryHolder();
        assertThatThrownBy(() -> service.updateSingleEntryAttributes(assetId, h.getId(),
                new HolderService.AttributeChange(null, "neu", null, null, true, false), INS, null, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mutually exclusive");
    }

    // ── T3-08 credit paths ───────────────────────────────────────────────────

    @Test
    void topUpIncrementsExistingRow() {
        AssetHolder existing = new AssetHolder();
        existing.setId(UUID.randomUUID());
        existing.setAssetId(assetId);
        existing.setInvestorId(investorId);
        existing.setWalletAddress(WALLET);
        existing.setNominalAmount(new BigDecimal("100"));
        when(holders.findActiveByAssetIdAndWalletAddress(assetId, WALLET)).thenReturn(Optional.of(existing));

        AssetHolder result = service.creditPosition(assetId, investorId, WALLET, new BigDecimal("50"), false,
                null, "REGISTRY_ADMIN", UUID.randomUUID());

        assertThat(result).isSameAs(existing);
        assertThat(existing.getNominalAmount()).isEqualByComparingTo("150");
        // no second row -> the unique wallet index is never hit
        ArgumentCaptor<AssetHolder> saved = ArgumentCaptor.forClass(AssetHolder.class);
        verify(holders).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
    }

    @Test
    void creditPositionInsertsWhenNoActiveRow() {
        when(holders.findActiveByAssetIdAndWalletAddress(assetId, WALLET)).thenReturn(Optional.empty());
        AssetHolder result = service.creditPosition(assetId, investorId, WALLET, new BigDecimal("50"), false,
                null, "REGISTRY_ADMIN", null);
        assertThat(result.getNominalAmount()).isEqualByComparingTo("50");
        assertThat(result.getInvestorId()).isEqualTo(investorId);
    }

    @Test
    void creditPositionRefusesWalletOfAnotherInvestor() {
        AssetHolder other = new AssetHolder();
        other.setInvestorId(UUID.randomUUID());
        other.setNominalAmount(BigDecimal.ONE);
        when(holders.findActiveByAssetIdAndWalletAddress(assetId, WALLET)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.creditPosition(assetId, investorId, WALLET, BigDecimal.TEN, false,
                null, "REGISTRY_ADMIN", null))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
    }

    @Test
    void creditPositionOnIndividualAssetCreatesSingleEntryWithReference() {
        asset.setEntryType(EntryType.INDIVIDUAL);
        when(holders.findActiveByAssetIdAndWalletAddress(assetId, WALLET)).thenReturn(Optional.empty());
        AssetHolder result = service.creditPosition(assetId, investorId, WALLET, BigDecimal.TEN, true,
                null, "REGISTRY_ADMIN", null);
        assertThat(result.getEntryType()).isEqualTo(EntryType.INDIVIDUAL);
        assertThat(result.getHolderReference()).startsWith("RW-");
        assertThat(result.getIsConsumer()).isTrue();
    }
}
