package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.events.HolderBlockCreatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockLiftedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SperrvermerkService dual-control audit propagation unit tests")
class SperrvermerkServiceTest {

    @Mock
    private HolderBlockRepository repository;

    @Mock
    private ApplicationEventPublisher events;

    private SperrvermerkService service;

    @BeforeEach
    void setUp() {
        service = new SperrvermerkService(repository, events);
        when(repository.save(any(HolderBlock.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static HolderBlock block() {
        HolderBlock block = new HolderBlock();
        block.setWalletAddress("0x" + "aa".repeat(20));
        block.setBlockType(HolderBlock.BlockType.GERICHTSBESCHLUSS);
        block.setLegalBasis("Court order Az. TEST-2026-001");
        return block;
    }

    @Test
    @DisplayName("create publishes the second approver on the audit event (previously omitted)")
    void create_publishesApproverOnEvent() {
        UUID createdBy = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        service.create(block(), createdBy, "REGISTRY_ADMIN", approver);

        ArgumentCaptor<HolderBlockCreatedEvent> captor = ArgumentCaptor.forClass(HolderBlockCreatedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approver);
    }

    @Test
    @DisplayName("lift publishes the second approver on the audit event (previously omitted)")
    void lift_publishesApproverOnEvent() {
        UUID blockId = UUID.randomUUID();
        UUID liftedBy = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        HolderBlock existing = block();
        existing.setStatus(HolderBlock.Status.ACTIVE);
        when(repository.findById(blockId)).thenReturn(Optional.of(existing));

        service.lift(blockId, liftedBy, "REGISTRY_ADMIN", "Debt settled", approver);

        ArgumentCaptor<HolderBlockLiftedEvent> captor = ArgumentCaptor.forClass(HolderBlockLiftedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approver);
    }

    @Test
    @DisplayName("autoExpire publishes a lift event with a null approver (system-driven, not dual-control-gated)")
    void autoExpire_publishesNullApprover() {
        HolderBlock expired = block();
        expired.setStatus(HolderBlock.Status.ACTIVE);
        when(repository.findExpiredActive(any())).thenReturn(List.of(expired));

        service.autoExpire();

        ArgumentCaptor<HolderBlockLiftedEvent> captor = ArgumentCaptor.forClass(HolderBlockLiftedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().dualControlApproverId()).isNull();
        assertThat(captor.getValue().actorRole()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("lift carries walletAddress/assetId in the payload so an on-chain sync listener can act on it ")
    void lift_publishesWalletAddressAndAssetIdForOnchainSync() {
        UUID blockId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        HolderBlock existing = block();
        existing.setAssetId(assetId);
        existing.setStatus(HolderBlock.Status.ACTIVE);
        when(repository.findById(blockId)).thenReturn(Optional.of(existing));

        service.lift(blockId, UUID.randomUUID(), "REGISTRY_ADMIN", "Debt settled", UUID.randomUUID());

        ArgumentCaptor<HolderBlockLiftedEvent> captor = ArgumentCaptor.forClass(HolderBlockLiftedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().payload()).containsEntry("walletAddress", existing.getWalletAddress());
        assertThat(captor.getValue().payload()).containsEntry("assetId", assetId.toString());
    }

    @Test
    @DisplayName("create carries assetId as empty string when the block is wallet-wide, not asset-specific")
    void create_publishesEmptyAssetIdWhenWalletWide() {
        service.create(block(), UUID.randomUUID(), "REGISTRY_ADMIN", null);

        ArgumentCaptor<HolderBlockCreatedEvent> captor = ArgumentCaptor.forClass(HolderBlockCreatedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().payload()).containsEntry("assetId", "");
    }
}
