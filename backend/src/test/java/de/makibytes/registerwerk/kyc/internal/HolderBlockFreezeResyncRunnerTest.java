package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.events.HolderBlockFreezeResyncRequestedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H5: the V10 resync re-emits the on-chain freeze of every block that is still legally blocking. A block in
 * {@code EXPIRY_REVIEW} has passed its date but keeps blocking until a human lifts it, so skipping it left
 * its wallet unfrozen on-chain.
 */
@DisplayName("HolderBlockFreezeResyncRunner (H5)")
class HolderBlockFreezeResyncRunnerTest {

    private final EntityManager em = mock(EntityManager.class);
    private final Query query = mock(Query.class);
    private final HolderBlockRepository repository = mock(HolderBlockRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private HolderBlockFreezeResyncRunner runner;

    @BeforeEach
    void setUp() {
        runner = new HolderBlockFreezeResyncRunner(repository, events);
        ReflectionTestUtils.setField(runner, "em", em);
        when(em.createNativeQuery(anyString())).thenReturn(query);
    }

    private HolderBlock block(HolderBlock.Status status) {
        HolderBlock b = new HolderBlock();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setWalletAddress("0x" + "ab".repeat(20));
        b.setLegalBasis("LG Frankfurt 2-04 O 1/26");
        b.setStatus(status);
        when(repository.findById(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    @DisplayName("an EXPIRY_REVIEW block (still blocking) is re-emitted")
    void expiryReviewBlockIsStillReEmitted() {
        HolderBlock review = block(HolderBlock.Status.EXPIRY_REVIEW);
        when(query.getResultList()).thenReturn(List.of(review.getId()));

        int emitted = runner.resyncPending();

        assertThat(emitted).isEqualTo(1);
        ArgumentCaptor<HolderBlockFreezeResyncRequestedEvent> captor =
                ArgumentCaptor.forClass(HolderBlockFreezeResyncRequestedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().holderBlockId()).isEqualTo(review.getId());
    }

    @Test
    @DisplayName("an ACTIVE block is re-emitted; lifted / expired / superseded blocks are not")
    void onlyBlockingBlocksAreReEmitted() {
        HolderBlock active = block(HolderBlock.Status.ACTIVE);
        HolderBlock lifted = block(HolderBlock.Status.LIFTED);
        HolderBlock expired = block(HolderBlock.Status.EXPIRED);
        HolderBlock superseded = block(HolderBlock.Status.SUPERSEDED);
        when(query.getResultList()).thenReturn(
                List.of(active.getId(), lifted.getId(), expired.getId(), superseded.getId(), UUID.randomUUID()));

        int emitted = runner.resyncPending();

        assertThat(emitted).isEqualTo(1);
        verify(events, times(1)).publishEvent(any(Object.class));
    }
}
