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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SperrvermerkService dual-control audit propagation unit tests")
class SperrvermerkServiceTest {

    @Mock
    private HolderBlockRepository repository;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private de.makibytes.registerwerk.deployment.api.AssetHolderRepository holders;

    @Mock
    private de.makibytes.registerwerk.customer.api.EntityTaskPort tasks;

    private SperrvermerkService service;

    @BeforeEach
    void setUp() {
        service = new SperrvermerkService(repository, events, holders, tasks, "");
        lenient().when(repository.save(any(HolderBlock.class))).thenAnswer(inv -> inv.getArgument(0));
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
    @DisplayName("6-25: a court order past expiry is NOT lifted (default: no auto-expire types); it stays blocking in EXPIRY_REVIEW")
    void autoExpire_courtOrderMovesToExpiryReview() {
        HolderBlock expired = block();
        expired.setEntityId(UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(expired, "id", UUID.randomUUID());
        expired.setStatus(HolderBlock.Status.ACTIVE);
        when(repository.findExpiredActive(any())).thenReturn(List.of(expired));

        service.autoExpire();

        assertThat(expired.getStatus()).isEqualTo(HolderBlock.Status.EXPIRY_REVIEW);
        assertThat(expired.getLiftedAt()).isNull();
        verify(events).publishEvent(any(de.makibytes.registerwerk.kyc.events.HolderBlockExpiryReviewEvent.class));
        verify(events, never()).publishEvent(any(HolderBlockLiftedEvent.class));
        verify(tasks).open(any(), org.mockito.ArgumentMatchers.eq(de.makibytes.registerwerk.customer.api.EntityTask.SPERRVERMERK_EXPIRY_REVIEW),
                any(), any(), any());
    }

    @Test
    @DisplayName("a type listed in auto-expire-types is still auto-lifted by the system (explicit opt-in)")
    void autoExpire_configuredTypeIsLifted() {
        SperrvermerkService opted = new SperrvermerkService(repository, events, holders, tasks, "pfandrecht");
        HolderBlock expired = block();
        expired.setBlockType(HolderBlock.BlockType.PFANDRECHT);
        expired.setStatus(HolderBlock.Status.ACTIVE);
        when(repository.findExpiredActive(any())).thenReturn(List.of(expired));

        opted.autoExpire();

        assertThat(expired.getStatus()).isEqualTo(HolderBlock.Status.EXPIRED);
        ArgumentCaptor<HolderBlockLiftedEvent> captor = ArgumentCaptor.forClass(HolderBlockLiftedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().dualControlApproverId()).isNull();
        assertThat(captor.getValue().actorRole()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("an expiry date in the past is rejected at creation (used to be accepted and lifted at the next 03:00)")
    void create_rejectsPastExpiry() {
        HolderBlock b = block();
        b.setExpiresAt(java.time.Instant.now().minusSeconds(60));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.create(b, UUID.randomUUID(), "REGISTRY_ADMIN", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
    }

    @Test
    @DisplayName("a court order with an expiry needs a reference and records the approver's expiry confirmation")
    void create_legalOrderExpiryNeedsReferenceAndApprover() {
        HolderBlock noRef = block();
        noRef.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.create(noRef, UUID.randomUUID(), "REGISTRY_ADMIN", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("courtRef");

        HolderBlock ok = block();
        ok.setCourtRef("Az. 2-04 O 123/26");
        ok.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
        HolderBlock saved = service.create(ok, UUID.randomUUID(), "REGISTRY_ADMIN", UUID.randomUUID());
        assertThat(saved.isExpiryConfirmedByApprover()).isTrue();
    }

    @Test
    @DisplayName("a wallet-only block takes the entityId of the single entity that holds the wallet")
    void create_resolvesEntityFromHolderRow() {
        UUID entityId = UUID.randomUUID();
        de.makibytes.registerwerk.deployment.api.AssetHolder h = new de.makibytes.registerwerk.deployment.api.AssetHolder();
        h.setInvestorId(entityId);
        when(holders.findByWalletAddressIn(any())).thenReturn(List.of(h));

        HolderBlock saved = service.create(block(), UUID.randomUUID(), "REGISTRY_ADMIN", null);

        assertThat(saved.getEntityId()).isEqualTo(entityId);
    }

    @Test
    @DisplayName("an EXPIRY_REVIEW block can still be lifted through the normal lift")
    void lift_fromExpiryReview() {
        UUID id = UUID.randomUUID();
        HolderBlock b = block();
        b.setStatus(HolderBlock.Status.EXPIRY_REVIEW);
        when(repository.findById(id)).thenReturn(Optional.of(b));

        assertThat(service.lift(id, UUID.randomUUID(), "REGISTRY_ADMIN", "order expired", UUID.randomUUID()).getStatus())
                .isEqualTo(HolderBlock.Status.LIFTED);
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
