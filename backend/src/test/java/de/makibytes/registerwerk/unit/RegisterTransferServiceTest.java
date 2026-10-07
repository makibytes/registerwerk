package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.EntryType;
import de.makibytes.registerwerk.registertransfer.api.RegisterTransfer;
import de.makibytes.registerwerk.registertransfer.api.RegisterTransferRepository;
import de.makibytes.registerwerk.registertransfer.api.TransferStatus;
import de.makibytes.registerwerk.registertransfer.internal.RegisterTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the §§21/22 register-transfer state machine and the §20 eWpRV
 * export package. The state machine is the highest-risk part: a transfer must
 * strictly move INITIATED -> EXPORTED -> HANDED_OVER -> COMPLETED, since each
 * step hands off legal control of the register to the successor operator.
 */
@ExtendWith(MockitoExtension.class)
class RegisterTransferServiceTest {

    @Mock private RegisterTransferRepository transferRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private AssetHolderRepository holderRepository;
    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private AuditApi auditApi;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private de.makibytes.registerwerk.finality.api.FinalityGate finalityGate;
    @Mock private de.makibytes.registerwerk.kyc.api.HolderBlockRepository blockRepository;
    @Mock private de.makibytes.registerwerk.customer.api.LegalEntityRepository entityRepository;
    @Mock private de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository bondTermsRepository;
    @Mock private de.makibytes.registerwerk.deployment.api.AssetCouponPaymentRepository couponPaymentRepository;
    @Mock private de.makibytes.registerwerk.asset.api.AssetDocumentRepository documentRepository;
    @Mock private de.makibytes.registerwerk.asset.api.RedemptionReadinessPort corporateActionPort;
    @Mock private de.makibytes.registerwerk.asset.api.OpenSubscriptionOrdersPort subscriptionOrdersPort;
    @Mock private de.makibytes.registerwerk.registertransfer.internal.OnchainHandoverVerifier handoverVerifier;

    @Mock private org.springframework.beans.factory.ObjectProvider<de.makibytes.registerwerk.asset.api.HandoverBlocker> handoverBlockers;
    private final java.util.List<de.makibytes.registerwerk.asset.api.HandoverBlocker> blockers = new java.util.ArrayList<>();

    private RegisterTransferService service;

    private static final UUID ASSET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RegisterTransferService(
                transferRepository, assetRepository, holderRepository, deploymentRepository,
                auditApi, new ObjectMapper(), eventPublisher, finalityGate,
                blockRepository, entityRepository, bondTermsRepository, couponPaymentRepository, documentRepository,
                corporateActionPort, subscriptionOrdersPort, handoverVerifier,
                new de.makibytes.registerwerk.shared.RegisterClock(java.time.Clock.systemUTC(),
                        java.time.ZoneId.of("Europe/Berlin")),
                null, handoverBlockers);
        lenient().when(handoverBlockers.orderedStream()).thenAnswer(inv -> blockers.stream());
        lenient().when(transferRepository.save(any(RegisterTransfer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Asset asset() {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setName("Test Bond");
        asset.setIsin("DE000TESTBND1");
        asset.setEntryType(EntryType.INDIVIDUAL);
        asset.setStatus(de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED);
        return asset;
    }

    // ── initiate ──────────────────────────────────────────────────────────────

    @Test
    void initiate_createsInitiatedTransfer_whenNoneInProgress() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(transferRepository.findFirstByAssetIdAndStatusNotInOrderByInitiatedAtDesc(
                eq(ASSET_ID), anyList())).thenReturn(Optional.empty());

        RegisterTransfer transfer = service.initiate(ASSET_ID, "Successor GmbH", "LEI123", "§22 handover", UUID.randomUUID());

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(transfer.getSuccessorName()).isEqualTo("Successor GmbH");
        verify(transferRepository).save(any(RegisterTransfer.class));
    }

    @Test
    void initiate_rejectsUnknownAsset() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiate(ASSET_ID, "Successor", "LEI", "reason", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initiate_rejectsASecondConcurrentTransferForTheSameAsset() {
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        RegisterTransfer inFlight = new RegisterTransfer();
        inFlight.setStatus(TransferStatus.EXPORTED);
        when(transferRepository.findFirstByAssetIdAndStatusNotInOrderByInitiatedAtDesc(
                eq(ASSET_ID), anyList())).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> service.initiate(ASSET_ID, "Successor", "LEI", "reason", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
        verify(transferRepository, never()).save(any());
    }

    // ── export ────────────────────────────────────────────────────────────────

    @Test
    void export_buildsManifestAndTransitionsToExported() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.INITIATED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(holder())));
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(deployment()));
        when(auditApi.findBySubject(eq("Asset"), eq(ASSET_ID), any()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(500), 0));

        byte[] json = service.export(transferId, UUID.randomUUID());

        assertThat(json).isNotEmpty();
        assertThat(new String(json)).contains("eWpRV").contains("Test Bond").contains("DE000TESTBND1");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.EXPORTED);
        assertThat(transfer.getExportHash()).startsWith("0x");
        assertThat(transfer.getExportManifest()).isNotNull();
        assertThat(transfer.getExportedAt()).isNotNull();
        verify(finalityGate).require(de.makibytes.registerwerk.finality.api.GatedOperation.REGISTER_EXTRACT_EXPORT,
                ASSET_ID, asset().getTokenStandard(), de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED);
    }

    @Test
    void export_blockedByFinalityGate_propagatesAndNeverTransitionsToExported() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.INITIATED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        doThrow(new de.makibytes.registerwerk.finality.api.FinalityNotReachedException(
                        new de.makibytes.registerwerk.finality.api.FinalityDecision.Blocked(
                                de.makibytes.registerwerk.finality.api.GatedOperation.REGISTER_EXTRACT_EXPORT,
                                ASSET_ID, de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED,
                                de.makibytes.registerwerk.finality.api.FinalityLevel.SAFE,
                                de.makibytes.registerwerk.finality.api.FinalityDecision.Blocked.Reason.BELOW_REQUIRED,
                                "not yet final")))
                .when(finalityGate).require(any(), any(), any(), any());

        assertThatThrownBy(() -> service.export(transferId, UUID.randomUUID()))
                .isInstanceOf(de.makibytes.registerwerk.finality.api.FinalityNotReachedException.class);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.INITIATED);
        verify(transferRepository, never()).save(any());
        verifyNoInteractions(holderRepository, deploymentRepository, auditApi);
    }

    // ── 9A-07: encumbrances block the handover ───────────────────────────────

    private RegisterTransfer initiatedTransfer(UUID transferId, TransferStatus status) {
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(status);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        return transfer;
    }

    @Test
    void exportRefusedWhileRepoOpen() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId, TransferStatus.INITIATED);
        blockers.add(id -> Optional.of("it is pledged as collateral in an open repo trade"));

        assertThatThrownBy(() -> service.export(transferId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Register handover refused").hasMessageContaining("open repo trade");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.INITIATED);
        verify(transferRepository, never()).save(any());
        verify(assetRepository, never()).save(any());
    }

    @Test
    void exportNamesEveryEncumbrance_lendingPositionAndUnresolvedTrade() {
        UUID transferId = UUID.randomUUID();
        initiatedTransfer(transferId, TransferStatus.INITIATED);
        blockers.add(id -> Optional.of("locked by an open lending position"));
        blockers.add(id -> Optional.empty());
        blockers.add(id -> Optional.of("a trade is still in flight (PAYMENT_UNRESOLVED)"));

        assertThatThrownBy(() -> service.export(transferId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open lending position").hasMessageContaining("PAYMENT_UNRESOLVED");
    }

    @Test
    void reExportIsRefusedToo_whenAPledgeWasOpenedAfterTheFirstExport() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId, TransferStatus.EXPORTED);
        blockers.add(id -> Optional.of("it is pledged as collateral in an open repo trade"));

        assertThatThrownBy(() -> service.export(transferId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Register handover refused");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.EXPORTED);
    }

    @Test
    void export_isReExportable_whenAlreadyExported() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.EXPORTED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        when(auditApi.findBySubject(eq("Asset"), eq(ASSET_ID), any()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(500), 0));

        assertThat(service.export(transferId, UUID.randomUUID())).isNotEmpty();
    }

    @Test
    void export_rejectsATransferThatIsAlreadyHandedOver() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setStatus(TransferStatus.HANDED_OVER);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.export(transferId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(assetRepository);
    }

    @Test
    void export_paginatesTheFullAuditTrail() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.INITIATED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());

        AuditEventView event1 = new AuditEventView(UUID.randomUUID(), "ASSET_CREATED", "Asset", ASSET_ID,
                UUID.randomUUID(), "REGISTRY_ADMIN", Map.of(), java.time.Instant.now(), 1L, "ab", null);
        // First page reports isLast=false, forcing the service to fetch a second page.
        when(auditApi.findBySubject(eq("Asset"), eq(ASSET_ID), argThat(p -> p.getPageNumber() == 0)))
                .thenReturn(new PageImpl<>(List.of(event1), Pageable.ofSize(500), 501));
        when(auditApi.findBySubject(eq("Asset"), eq(ASSET_ID), argThat(p -> p.getPageNumber() == 1)))
                .thenReturn(new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(1, 500), 501));

        byte[] json = service.export(transferId, UUID.randomUUID());

        assertThat(new String(json)).contains("ASSET_CREATED");
        verify(auditApi, times(2)).findBySubject(eq("Asset"), eq(ASSET_ID), any());
    }

    // ── recordOnchainHandover / complete / cancel ────────────────────────────

    @Test
    void recordOnchainHandover_requiresExportedFirst() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setStatus(TransferStatus.INITIATED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.recordOnchainHandover(transferId, "0xhash", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Export must precede");
    }

    @Test
    void recordOnchainHandover_transitionsToHandedOver() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setStatus(TransferStatus.EXPORTED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        RegisterTransfer result = service.recordOnchainHandover(transferId, "0xabc", UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TransferStatus.HANDED_OVER);
        assertThat(result.getOnchainTxHash()).isEqualTo("0xabc");
    }

    @Test
    void complete_requiresHandedOverFirst() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setStatus(TransferStatus.EXPORTED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.complete(transferId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HANDED_OVER");
    }

    @Test
    void complete_transitionsToCompleted() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setStatus(TransferStatus.HANDED_OVER);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        RegisterTransfer result = service.complete(transferId, UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        // transfer.assetId is null in this fixture -> asset lookup resolves empty -> gate skipped
        // entirely (mirrors the pre-existing "asset disappeared" tolerance, see the warn-log branch).
        verifyNoInteractions(finalityGate);
    }

    @Test
    void complete_withKnownAsset_consultsFinalityGateAndFlipsAssetStatus() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.HANDED_OVER);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));

        RegisterTransfer result = service.complete(transferId, UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(finalityGate).require(de.makibytes.registerwerk.finality.api.GatedOperation.REGISTER_TRANSFER_COMPLETE,
                ASSET_ID, asset().getTokenStandard(), de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED);
        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getStatus()).isEqualTo(de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFERRED_OUT);
    }

    @Test
    void complete_blockedByFinalityGate_propagatesAndNeverTransitionsToCompleted() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.HANDED_OVER);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        doThrow(new de.makibytes.registerwerk.finality.api.FinalityNotReachedException(
                        new de.makibytes.registerwerk.finality.api.FinalityDecision.Blocked(
                                de.makibytes.registerwerk.finality.api.GatedOperation.REGISTER_TRANSFER_COMPLETE,
                                ASSET_ID, de.makibytes.registerwerk.finality.api.FinalityLevel.FINALIZED,
                                de.makibytes.registerwerk.finality.api.FinalityLevel.PROVISIONAL,
                                de.makibytes.registerwerk.finality.api.FinalityDecision.Blocked.Reason.BELOW_REQUIRED,
                                "not yet final")))
                .when(finalityGate).require(any(), any(), any(), any());

        assertThatThrownBy(() -> service.complete(transferId, UUID.randomUUID()))
                .isInstanceOf(de.makibytes.registerwerk.finality.api.FinalityNotReachedException.class);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.HANDED_OVER);
        verify(transferRepository, never()).save(any());
        verify(assetRepository, never()).save(any());
    }

    @Test
    void cancel_rejectsACompletedTransfer() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setStatus(TransferStatus.COMPLETED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.cancel(transferId, "changed mind", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_marksCancelledAndAppendsReason() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setStatus(TransferStatus.INITIATED);
        transfer.setReason("§22 handover");
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        RegisterTransfer result = service.cancel(transferId, "successor withdrew", UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(result.getReason()).isEqualTo("§22 handover | cancelled: successor withdrew");
    }

    @Test
    void anyOperation_rejectsAnUnknownTransferId() {
        UUID transferId = UUID.randomUUID();
        when(transferRepository.findById(transferId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(transferId, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown register transfer");
    }

    // ── T3-07: freeze, content hash, completion re-check, per-deployment handover ─────────────

    private RegisterTransfer initiatedTransfer(UUID transferId) {
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.INITIATED);
        lenient().when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        lenient().when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of());
        lenient().when(auditApi.findBySubject(eq("Asset"), eq(ASSET_ID), any()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(500), 0));
        return transfer;
    }

    @Test
    void exportFreezesTrading() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId);
        Asset asset = asset();
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(holder())));

        service.export(transferId, UUID.randomUUID());

        assertThat(asset.getStatus()).isEqualTo(de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFER_PENDING);
        assertThat(asset.getStatus().isRegisterFrozen()).isTrue();
        assertThat(transfer.getPreviousAssetStatus()).isEqualTo("ISSUED");
        verify(assetRepository).save(asset);
    }

    @Test
    void contentHashIsSeparateFromTheEnvelopeTimestampAndStableAcrossReExport() throws Exception {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        AssetHolder h = holder();
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(h)));

        service.export(transferId, UUID.randomUUID());
        String contentHash1 = transfer.getRegisterContentHash();
        String fileHash1 = transfer.getExportHash();
        Thread.sleep(5);
        service.export(transferId, UUID.randomUUID());

        assertThat(contentHash1).startsWith("0x");
        assertThat(transfer.getRegisterContentHash()).isEqualTo(contentHash1);
        assertThat(transfer.getExportHash()).isNotEqualTo(fileHash1); // envelope (exportedAt) differs
        assertThat(transfer.getExportManifest()).containsKeys("registerContentHash", "registerContentSections",
                "holderBlocks", "bondTerms", "openCorporateActions", "openSubscriptionOrders", "termSheetDocumentHash");
    }

    @Test
    void completeRefusesWhenRegisterChangedSinceExport() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId);
        Asset asset = asset();
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        AssetHolder h = holder();
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(h)));
        service.export(transferId, UUID.randomUUID());
        transfer.setStatus(TransferStatus.HANDED_OVER);

        h.setNominalAmount(BigDecimal.valueOf(999)); // holder sync moved a balance after the export

        assertThatThrownBy(() -> service.complete(transferId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Register changed since the export")
                .hasMessageContaining("holders");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.HANDED_OVER);
        assertThat(asset.getStatus()).isEqualTo(de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFER_PENDING);
    }

    @Test
    void completeSucceedsWhenRegisterUnchangedSinceExport() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId);
        Asset asset = asset();
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(holder())));
        service.export(transferId, UUID.randomUUID());
        transfer.setStatus(TransferStatus.HANDED_OVER);

        RegisterTransfer done = service.complete(transferId, UUID.randomUUID());

        assertThat(done.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(asset.getStatus()).isEqualTo(de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFERRED_OUT);
    }

    @Test
    void packageContainsActiveHolderBlocks() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset()));
        AssetHolder h = holder();
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(h)));
        de.makibytes.registerwerk.kyc.api.HolderBlock assetBlock = new de.makibytes.registerwerk.kyc.api.HolderBlock();
        assetBlock.setAssetId(ASSET_ID);
        assetBlock.setBlockType(de.makibytes.registerwerk.kyc.api.HolderBlock.BlockType.PFAENDUNG);
        de.makibytes.registerwerk.kyc.api.HolderBlock walletBlock = new de.makibytes.registerwerk.kyc.api.HolderBlock();
        walletBlock.setWalletAddress(h.getWalletAddress());
        walletBlock.setBlockType(de.makibytes.registerwerk.kyc.api.HolderBlock.BlockType.GERICHTSBESCHLUSS);
        de.makibytes.registerwerk.kyc.api.HolderBlock unrelated = new de.makibytes.registerwerk.kyc.api.HolderBlock();
        unrelated.setAssetId(UUID.randomUUID());
        unrelated.setBlockType(de.makibytes.registerwerk.kyc.api.HolderBlock.BlockType.INSOLVENZ);
        when(blockRepository.findByStatusInOrderByCreatedAtDesc(de.makibytes.registerwerk.kyc.api.HolderBlock.BLOCKING))
                .thenReturn(List.of(assetBlock, walletBlock, unrelated));

        service.export(transferId, UUID.randomUUID());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) transfer.getExportManifest().get("holderBlocks");
        assertThat(blocks).extracting(b -> b.get("blockType")).containsExactlyInAnyOrder("PFAENDUNG", "GERICHTSBESCHLUSS");
    }

    @Test
    void exportRefusedWhileACorporateActionIsStillPayable() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId);
        Asset asset = asset();
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(corporateActionPort.openActions(ASSET_ID)).thenReturn(List.of(
                new de.makibytes.registerwerk.asset.api.RedemptionReadinessPort.OpenAction(UUID.randomUUID(), "COUPON",
                        "ANNOUNCED", java.time.LocalDate.now().minusDays(1), java.time.LocalDate.now().plusDays(3))));

        assertThatThrownBy(() -> service.export(transferId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("settle or cancel");
        assertThat(asset.getStatus()).isEqualTo(de.makibytes.registerwerk.asset.api.AssetStatus.ISSUED);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.INITIATED);
    }

    @Test
    void cancelRestoresThePreviousAssetStatus() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = initiatedTransfer(transferId);
        transfer.setReason("r");
        Asset asset = asset();
        asset.setStatus(de.makibytes.registerwerk.asset.api.AssetStatus.SUSPENDED);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(holderRepository.findActiveByAssetId(eq(ASSET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        service.export(transferId, UUID.randomUUID());
        assertThat(asset.getStatus()).isEqualTo(de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFER_PENDING);

        service.cancel(transferId, "successor withdrew", UUID.randomUUID());

        assertThat(asset.getStatus()).isEqualTo(de.makibytes.registerwerk.asset.api.AssetStatus.SUSPENDED);
    }

    @Test
    void initiateRefusedForTransferredOutAsset() {
        Asset asset = asset();
        asset.setStatus(de.makibytes.registerwerk.asset.api.AssetStatus.TRANSFERRED_OUT);
        when(assetRepository.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.initiate(ASSET_ID, "S", "L", "r", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already transferred out");
    }

    private static AssetDeployment evmDeployment(UUID id) {
        AssetDeployment d = deployment();
        d.setId(id);
        d.setChain(de.makibytes.registerwerk.chain.api.Chain.ETHEREUM);
        return d;
    }

    @Test
    void evmHandoverIsVerifiedOnChainAndMismatchIsRefused() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.EXPORTED);
        String successor = "0x" + "cc".repeat(20);
        transfer.setSuccessorOnchainAddress(successor);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        UUID depId = UUID.randomUUID();
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(evmDeployment(depId)));
        when(handoverVerifier.observeController(any()))
                .thenReturn(new de.makibytes.registerwerk.registertransfer.internal.OnchainHandoverVerifier.Observation(
                        "registry", "0x" + "dd".repeat(20)))
                .thenReturn(new de.makibytes.registerwerk.registertransfer.internal.OnchainHandoverVerifier.Observation(
                        "registry", successor.toUpperCase().replace("0X", "0x")));

        assertThatThrownBy(() -> service.recordOnchainHandover(transferId, depId, "0xtx", false, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NOT verified");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.EXPORTED);

        RegisterTransfer ok = service.recordOnchainHandover(transferId, depId, "0xtx", false, UUID.randomUUID());
        assertThat(ok.getStatus()).isEqualTo(TransferStatus.HANDED_OVER);
        assertThat(ok.getOnchainHandovers()).hasSize(1);
        assertThat(ok.getOnchainHandovers().get(0)).containsEntry("verified", true).containsEntry("txHash", "0xtx");
    }

    @Test
    void nonEvmHandoverNeedsExplicitAttestationAndEveryDeploymentMustBeRecorded() {
        UUID transferId = UUID.randomUUID();
        RegisterTransfer transfer = new RegisterTransfer();
        transfer.setAssetId(ASSET_ID);
        transfer.setStatus(TransferStatus.EXPORTED);
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        UUID solId = UUID.randomUUID();
        UUID canId = UUID.randomUUID();
        AssetDeployment sol = deployment();
        sol.setId(solId);
        sol.setChain(de.makibytes.registerwerk.chain.api.Chain.SOLANA);
        AssetDeployment can = deployment();
        can.setId(canId);
        can.setChain(de.makibytes.registerwerk.chain.api.Chain.CANTON);
        when(deploymentRepository.findByAssetId(ASSET_ID)).thenReturn(List.of(sol, can));

        assertThatThrownBy(() -> service.recordOnchainHandover(transferId, solId, "sig", false, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("attested=true");
        assertThatThrownBy(() -> service.recordOnchainHandover(transferId, null, "sig", true, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("deploymentId is required");

        assertThat(service.recordOnchainHandover(transferId, solId, "sig", true, UUID.randomUUID()).getStatus())
                .isEqualTo(TransferStatus.EXPORTED);
        RegisterTransfer done = service.recordOnchainHandover(transferId, canId, "upd", true, UUID.randomUUID());
        assertThat(done.getStatus()).isEqualTo(TransferStatus.HANDED_OVER);
        assertThat(done.getOnchainHandovers()).extracting(r -> r.get("method")).containsOnly("OPERATOR_ATTESTED");
    }

    // ── listForAsset ──────────────────────────────────────────────────────────

    @Test
    void listForAsset_delegatesToRepository() {
        when(transferRepository.findByAssetIdOrderByInitiatedAtDesc(ASSET_ID)).thenReturn(List.of());
        assertThat(service.listForAsset(ASSET_ID)).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static AssetHolder holder() {
        AssetHolder h = new AssetHolder();
        h.setInvestorId(UUID.randomUUID());
        h.setWalletAddress("0x" + "aa".repeat(20));
        h.setNominalAmount(BigDecimal.valueOf(1000));
        h.setEntryType(EntryType.INDIVIDUAL);
        h.setHolderReference("HREF-1");
        return h;
    }

    private static AssetDeployment deployment() {
        AssetDeployment d = new AssetDeployment();
        d.setContractAddress("0x" + "bb".repeat(20));
        d.setDeployedByTx("0xdeploytx");
        return d;
    }
}
