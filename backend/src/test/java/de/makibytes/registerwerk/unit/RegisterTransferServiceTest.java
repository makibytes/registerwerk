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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
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

    private RegisterTransferService service;

    private static final UUID ASSET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RegisterTransferService(
                transferRepository, assetRepository, holderRepository, deploymentRepository,
                auditApi, new ObjectMapper(), eventPublisher);
        lenient().when(transferRepository.save(any(RegisterTransfer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Asset asset() {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setName("Test Bond");
        asset.setIsin("DE000TESTBND1");
        asset.setEntryType(EntryType.INDIVIDUAL);
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
