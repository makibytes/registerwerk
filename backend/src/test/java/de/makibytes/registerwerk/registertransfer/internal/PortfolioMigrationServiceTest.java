package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.registertransfer.api.PortfolioMigrationRequest;
import de.makibytes.registerwerk.registertransfer.api.PortfolioMigrationRequestRepository;
import de.makibytes.registerwerk.registertransfer.api.TransferStatus;
import de.makibytes.registerwerk.registertransfer.events.PortfolioMigrationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioMigrationService unit tests")
class PortfolioMigrationServiceTest {

    @Mock private PortfolioMigrationRequestRepository repository;
    @Mock private AssetHolderRepository holderRepository;
    @Mock private AssetRepository assetRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HolderBlockGate holderBlockGate;

    private PortfolioMigrationService service;

    private PortfolioMigrationServiceTest init() {
        service = new PortfolioMigrationService(repository, holderRepository, assetRepository, objectMapper,
                eventPublisher, holderBlockGate);
        // Compliant-by-default: tests exercising the block check override this explicitly.
        lenient().when(holderBlockGate.isBlocked(any(), any())).thenReturn(false);
        return this;
    }

    private static AssetHolder holder(UUID id, UUID investorId, UUID assetId) {
        AssetHolder h = new AssetHolder();
        ReflectionTestUtils.setField(h, "id", id);
        h.setInvestorId(investorId);
        h.setAssetId(assetId);
        h.setWalletAddress("0x" + "11".repeat(20));
        h.setNominalAmount(new BigDecimal("100"));
        return h;
    }

    @Test
    @DisplayName("initiate rejects a second concurrent migration for the same holder")
    void initiate_rejectsDuplicateInProgress() {
        init();
        UUID holderId = UUID.randomUUID();
        when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder(holderId, UUID.randomUUID(), UUID.randomUUID())));
        when(repository.existsByHolderIdAndStatusNotIn(eq(holderId), any())).thenReturn(true);

        assertThatThrownBy(() -> service.initiate(holderId, "leaving", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("initiate creates an INITIATED request scoped to the holder's investor/asset")
    void initiate_createsRequest() {
        init();
        UUID holderId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder(holderId, investorId, assetId)));
        when(repository.existsByHolderIdAndStatusNotIn(eq(holderId), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PortfolioMigrationRequest result = service.initiate(holderId, "leaving", UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(result.getInvestorEntityId()).isEqualTo(investorId);
        assertThat(result.getAssetId()).isEqualTo(assetId);
    }

    @Test
    @DisplayName("initiate refuses a legally blocked holding — the earliest point to stop it")
    void initiate_refusesBlockedHolder() {
        init();
        UUID holderId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        AssetHolder blockedHolder = holder(holderId, investorId, UUID.randomUUID());
        when(holderRepository.findById(holderId)).thenReturn(Optional.of(blockedHolder));
        when(repository.existsByHolderIdAndStatusNotIn(eq(holderId), any())).thenReturn(false);
        when(holderBlockGate.isBlocked(investorId, blockedHolder.getWalletAddress())).thenReturn(true);

        assertThatThrownBy(() -> service.initiate(holderId, "leaving", UUID.randomUUID()))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("Sperrvermerk");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("export requires a destination wallet to already be set")
    void export_requiresDestinationSet() {
        init();
        UUID migrationId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.INITIATED);
        migration.setHolderId(UUID.randomUUID());
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));

        assertThatThrownBy(() -> service.export(migrationId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Destination wallet");
    }

    @Test
    @DisplayName("recordOnchainTransfer requires the migration to already be EXPORTED")
    void recordOnchainTransfer_requiresExported() {
        init();
        UUID migrationId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.INITIATED);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));

        assertThatThrownBy(() -> service.recordOnchainTransfer(migrationId, "0xabc", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("complete requires the migration to already be HANDED_OVER")
    void complete_requiresHandedOver() {
        init();
        UUID migrationId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.EXPORTED);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));

        assertThatThrownBy(() -> service.complete(migrationId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("initiate rejects an unknown asset holder")
    void initiate_rejectsUnknownHolder() {
        init();
        UUID holderId = UUID.randomUUID();
        when(holderRepository.findById(holderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiate(holderId, "leaving", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(holderId.toString());
    }

    @Test
    @DisplayName("setDestination records the registrar/wallet and touches updatedAt")
    void setDestination_recordsDestination() {
        init();
        UUID migrationId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.INITIATED);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PortfolioMigrationRequest result = service.setDestination(
                migrationId, "Successor Registrar AG", "REG-123", "0x" + "22".repeat(20), UUID.randomUUID());

        assertThat(result.getDestinationRegistrarName()).isEqualTo("Successor Registrar AG");
        assertThat(result.getDestinationRegistrarIdentifier()).isEqualTo("REG-123");
        assertThat(result.getDestinationWalletAddress()).isEqualTo("0x" + "22".repeat(20));
    }

    @Test
    @DisplayName("setDestination rejects an unknown migration id")
    void setDestination_rejectsUnknownMigration() {
        init();
        UUID migrationId = UUID.randomUUID();
        when(repository.findById(migrationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setDestination(migrationId, "Reg", "ID", "0xabc", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(migrationId.toString());
    }

    @Test
    @DisplayName("export rejects a migration not in INITIATED or EXPORTED status")
    void export_rejectsWrongStatus() {
        init();
        UUID migrationId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.COMPLETED);
        migration.setDestinationWalletAddress("0x" + "33".repeat(20));
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));

        assertThatThrownBy(() -> service.export(migrationId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIATED or already-EXPORTED");
    }

    @Test
    @DisplayName("export builds the manifest, hashes it, marks EXPORTED, and publishes an event — asset present")
    void export_buildsManifestWithAsset() {
        init();
        UUID migrationId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();

        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.INITIATED);
        migration.setHolderId(holderId);
        migration.setAssetId(assetId);
        migration.setInvestorEntityId(investorId);
        migration.setDestinationRegistrarName("Successor Registrar AG");
        migration.setDestinationWalletAddress("0x" + "44".repeat(20));
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssetHolder holderRow = holder(holderId, investorId, assetId);
        when(holderRepository.findById(holderId)).thenReturn(Optional.of(holderRow));

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setName("Test Bond");
        asset.setIsin("DE000TESTBND1");
        asset.setTokenStandard(TokenStandard.ERC20);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));

        byte[] json = service.export(migrationId, UUID.randomUUID());

        assertThat(json).isNotEmpty();
        assertThat(migration.getStatus()).isEqualTo(TransferStatus.EXPORTED);
        assertThat(migration.getExportHash()).startsWith("0x");
        assertThat(migration.getExportManifest()).containsKeys("asset", "holding");
        assertThat(migration.getExportedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(PortfolioMigrationEvent.class));
    }

    @Test
    @DisplayName("export tolerates a since-deleted asset (empty asset snapshot, no failure)")
    void export_toleratesMissingAsset() {
        init();
        UUID migrationId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();

        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.EXPORTED); // re-export path
        migration.setHolderId(holderId);
        migration.setAssetId(assetId);
        migration.setInvestorEntityId(investorId);
        migration.setDestinationWalletAddress("0x" + "55".repeat(20));
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder(holderId, investorId, assetId)));
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        byte[] json = service.export(migrationId, UUID.randomUUID());

        assertThat(json).isNotEmpty();
        assertThat(migration.getExportManifest().get("asset")).isEqualTo(java.util.Map.of());
    }

    @Test
    @DisplayName("recordOnchainTransfer stores the tx hash, moves to HANDED_OVER, and publishes an event")
    void recordOnchainTransfer_success() {
        init();
        UUID migrationId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.EXPORTED);
        migration.setHolderId(holderId);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(holderRepository.findById(holderId))
                .thenReturn(Optional.of(holder(holderId, UUID.randomUUID(), UUID.randomUUID())));

        PortfolioMigrationRequest result = service.recordOnchainTransfer(migrationId, "0xdeadbeef", UUID.randomUUID());

        assertThat(result.getOnchainTxHash()).isEqualTo("0xdeadbeef");
        assertThat(result.getStatus()).isEqualTo(TransferStatus.HANDED_OVER);
        verify(eventPublisher).publishEvent(any(PortfolioMigrationEvent.class));
    }

    @Test
    @DisplayName("recordOnchainTransfer refuses a holding blocked mid-flight, after initiate already passed (defense-in-depth)")
    void recordOnchainTransfer_refusesBlockedHolder() {
        init();
        UUID migrationId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.EXPORTED);
        migration.setHolderId(holderId);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        AssetHolder blockedHolder = holder(holderId, investorId, UUID.randomUUID());
        when(holderRepository.findById(holderId)).thenReturn(Optional.of(blockedHolder));
        when(holderBlockGate.isBlocked(investorId, blockedHolder.getWalletAddress())).thenReturn(true);

        assertThatThrownBy(() -> service.recordOnchainTransfer(migrationId, "0xdeadbeef", UUID.randomUUID()))
                .isInstanceOf(de.makibytes.registerwerk.shared.ComplianceGateException.class)
                .hasMessageContaining("Sperrvermerk");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("complete marks COMPLETED, sets completedAt, and publishes an event")
    void complete_success() {
        init();
        UUID migrationId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.HANDED_OVER);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PortfolioMigrationRequest result = service.complete(migrationId, UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(PortfolioMigrationEvent.class));
    }

    @Test
    @DisplayName("complete closes the source register entry — the previously-missing step")
    void complete_closesSourceRegisterEntry() {
        init();
        UUID migrationId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.HANDED_OVER);
        migration.setHolderId(holderId);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AssetHolder holderRow = holder(holderId, UUID.randomUUID(), UUID.randomUUID());
        when(holderRepository.findById(holderId)).thenReturn(Optional.of(holderRow));

        service.complete(migrationId, UUID.randomUUID());

        assertThat(holderRow.getRemovedAt()).isNotNull();
        verify(holderRepository).save(holderRow);
        verify(eventPublisher).publishEvent(any(de.makibytes.registerwerk.asset.events.HolderRemovedEvent.class));
    }

    @Test
    @DisplayName("complete tolerates a holder that has since disappeared (no crash, just a warning)")
    void complete_toleratesMissingHolder() {
        init();
        UUID migrationId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.HANDED_OVER);
        migration.setHolderId(holderId);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(holderRepository.findById(holderId)).thenReturn(Optional.empty());

        PortfolioMigrationRequest result = service.complete(migrationId, UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(holderRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancel rejects a COMPLETED migration")
    void cancel_rejectsCompleted() {
        init();
        UUID migrationId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.COMPLETED);
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));

        assertThatThrownBy(() -> service.cancel(migrationId, "too late", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("cancel marks CANCELLED, appends the reason, and publishes an event")
    void cancel_success() {
        init();
        UUID migrationId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        migration.setStatus(TransferStatus.INITIATED);
        migration.setReason("Customer offboarded: contract terminated");
        when(repository.findById(migrationId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PortfolioMigrationRequest result = service.cancel(migrationId, "duplicate request", UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(result.getReason()).contains("Customer offboarded: contract terminated")
                .contains("cancelled: duplicate request");
        verify(eventPublisher).publishEvent(any(PortfolioMigrationEvent.class));
    }

    @Test
    @DisplayName("listForInvestor delegates to the repository ordered lookup")
    void listForInvestor_delegates() {
        init();
        UUID investorId = UUID.randomUUID();
        PortfolioMigrationRequest migration = new PortfolioMigrationRequest();
        when(repository.findByInvestorEntityIdOrderByInitiatedAtDesc(investorId)).thenReturn(List.of(migration));

        List<PortfolioMigrationRequest> result = service.listForInvestor(investorId);

        assertThat(result).containsExactly(migration);
    }
}
