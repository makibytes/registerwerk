package de.makibytes.registerwerk.accessreview.internal;

import de.makibytes.registerwerk.accessreview.api.AccessReviewCampaign;
import de.makibytes.registerwerk.accessreview.api.AccessReviewCampaignRepository;
import de.makibytes.registerwerk.accessreview.api.AccessReviewDecision;
import de.makibytes.registerwerk.accessreview.api.AccessReviewItem;
import de.makibytes.registerwerk.accessreview.api.AccessReviewItemRepository;
import de.makibytes.registerwerk.accessreview.api.AccessReviewStatus;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccessReviewService unit tests (Track 7-3)")
class AccessReviewServiceTest {

    @Mock private AccessReviewCampaignRepository campaignRepository;
    @Mock private AccessReviewItemRepository itemRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private ApplicationEventPublisher events;

    private AccessReviewService service;

    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AccessReviewService(campaignRepository, itemRepository, appUserRepository, events);
    }

    private static AppUser user(String email, AppUserRole... roles) {
        AppUser u = new AppUser();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setEmail(email);
        u.setFullName(email);
        u.setRoles(Set.of(roles));
        return u;
    }

    private static AccessReviewCampaign openCampaign(UUID id) {
        AccessReviewCampaign c = new AccessReviewCampaign();
        ReflectionTestUtils.setField(c, "id", id);
        c.setStatus(AccessReviewStatus.OPEN);
        return c;
    }

    @Test
    @DisplayName("startCampaign snapshots every enabled account's roles into items")
    void startCampaign_snapshotsEnabledUsers() {
        when(campaignRepository.save(any(AccessReviewCampaign.class))).thenAnswer(inv -> {
            AccessReviewCampaign c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
        when(appUserRepository.findByEnabledTrueOrderByEmailAsc())
                .thenReturn(List.of(user("a@test.local", AppUserRole.REGISTRY_ADMIN), user("b@test.local", AppUserRole.INVESTOR)));
        when(itemRepository.save(any(AccessReviewItem.class))).thenAnswer(inv -> inv.getArgument(0));

        AccessReviewCampaign campaign = service.startCampaign("Q3 review", null, actorId, "REGISTRY_ADMIN");

        assertThat(campaign.getStartedBy()).isEqualTo(actorId);
        ArgumentCaptor<AccessReviewItem> captor = ArgumentCaptor.forClass(AccessReviewItem.class);
        verify(itemRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AccessReviewItem::getEmailSnapshot)
                .containsExactlyInAnyOrder("a@test.local", "b@test.local");
        assertThat(captor.getAllValues()).extracting(AccessReviewItem::getRolesSnapshot)
                .containsExactlyInAnyOrder("REGISTRY_ADMIN", "INVESTOR");
        verify(events).publishEvent(any(de.makibytes.registerwerk.accessreview.events.AccessReviewCampaignStartedEvent.class));
    }

    @Test
    @DisplayName("recordDecision(CONFIRMED) does not touch the account")
    void recordDecision_confirmed_leavesAccountUntouched() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AccessReviewItem item = new AccessReviewItem();
        ReflectionTestUtils.setField(item, "id", itemId);
        item.setCampaignId(campaignId);
        item.setAppUserId(UUID.randomUUID());
        item.setEmailSnapshot("reviewed@test.local");
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign(campaignId)));
        when(itemRepository.findByCampaignIdAndId(campaignId, itemId)).thenReturn(Optional.of(item));
        when(itemRepository.save(any(AccessReviewItem.class))).thenAnswer(inv -> inv.getArgument(0));

        AccessReviewItem result = service.recordDecision(campaignId, itemId, AccessReviewDecision.CONFIRMED,
                "still needed", actorId, "REGISTRY_ADMIN");

        assertThat(result.getDecision()).isEqualTo(AccessReviewDecision.CONFIRMED);
        assertThat(result.getReviewedBy()).isEqualTo(actorId);
        verify(appUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordDecision(REVOKED) disables the underlying account")
    void recordDecision_revoked_disablesAccount() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        AccessReviewItem item = new AccessReviewItem();
        ReflectionTestUtils.setField(item, "id", itemId);
        item.setCampaignId(campaignId);
        item.setAppUserId(targetUserId);
        item.setEmailSnapshot("stale@test.local");
        AppUser target = user("stale@test.local", AppUserRole.TRADER);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign(campaignId)));
        when(itemRepository.findByCampaignIdAndId(campaignId, itemId)).thenReturn(Optional.of(item));
        when(itemRepository.save(any(AccessReviewItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(appUserRepository.findById(targetUserId)).thenReturn(Optional.of(target));

        service.recordDecision(campaignId, itemId, AccessReviewDecision.REVOKED,
                "left the company", actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a reviewer cannot record a decision on their own item")
    void recordDecision_selfReview_isRejected() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AccessReviewItem item = new AccessReviewItem();
        ReflectionTestUtils.setField(item, "id", itemId);
        item.setCampaignId(campaignId);
        item.setAppUserId(actorId);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign(campaignId)));
        when(itemRepository.findByCampaignIdAndId(campaignId, itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.recordDecision(campaignId, itemId, AccessReviewDecision.CONFIRMED,
                null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("recordDecision rejects PENDING as an explicit decision")
    void recordDecision_pending_isRejected() {
        assertThatThrownBy(() -> service.recordDecision(UUID.randomUUID(), UUID.randomUUID(),
                AccessReviewDecision.PENDING, null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recordDecision rejects a decision on an already-closed campaign")
    void recordDecision_closedCampaign_isRejected() {
        UUID campaignId = UUID.randomUUID();
        AccessReviewCampaign closed = openCampaign(campaignId);
        closed.setStatus(AccessReviewStatus.CLOSED);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.recordDecision(campaignId, UUID.randomUUID(),
                AccessReviewDecision.CONFIRMED, null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("closeCampaign rejects closing while items are still PENDING")
    void closeCampaign_withPendingItems_isRejected() {
        UUID campaignId = UUID.randomUUID();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign(campaignId)));
        when(itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.PENDING)).thenReturn(2L);

        assertThatThrownBy(() -> service.closeCampaign(campaignId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class);
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("closeCampaign succeeds once every item has a decision")
    void closeCampaign_allDecided_succeeds() {
        UUID campaignId = UUID.randomUUID();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign(campaignId)));
        when(itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.PENDING)).thenReturn(0L);
        when(itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.REVOKED)).thenReturn(1L);
        when(campaignRepository.save(any(AccessReviewCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        AccessReviewCampaign result = service.closeCampaign(campaignId, actorId, "REGISTRY_ADMIN");

        assertThat(result.getStatus()).isEqualTo(AccessReviewStatus.CLOSED);
        assertThat(result.getClosedBy()).isEqualTo(actorId);
        verify(events).publishEvent(any(de.makibytes.registerwerk.accessreview.events.AccessReviewCampaignClosedEvent.class));
    }

    @Test
    @DisplayName("getCampaign throws EntityNotFoundException for an unknown id")
    void getCampaign_unknown_throws() {
        UUID campaignId = UUID.randomUUID();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCampaign(campaignId)).isInstanceOf(EntityNotFoundException.class);
    }
}
