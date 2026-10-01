package de.makibytes.registerwerk.accessreview.internal;

import de.makibytes.registerwerk.accessreview.api.AccessReviewCampaign;
import de.makibytes.registerwerk.accessreview.api.AccessReviewCampaignRepository;
import de.makibytes.registerwerk.accessreview.api.AccessReviewDecision;
import de.makibytes.registerwerk.accessreview.api.AccessReviewItem;
import de.makibytes.registerwerk.accessreview.api.AccessReviewItemRepository;
import de.makibytes.registerwerk.accessreview.api.AccessReviewStatus;
import de.makibytes.registerwerk.auth.api.AccountAccessPort;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
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
import static org.mockito.Mockito.lenient;
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
    @Mock private AccountAccessPort accountAccess;

    private AccessReviewService service;

    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AccessReviewService(campaignRepository, itemRepository, appUserRepository, events,
                accountAccess, List.of("REGISTRY_ADMIN+COMPLIANCE_OFFICER"));
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

    private AccessReviewItem item(UUID campaignId, UUID itemId, AppUser target) {
        AccessReviewItem item = new AccessReviewItem();
        ReflectionTestUtils.setField(item, "id", itemId);
        item.setCampaignId(campaignId);
        item.setAppUserId(target.getId());
        item.setEmailSnapshot(target.getEmail());
        item.setRolesSnapshot(target.getRoles().stream().map(Enum::name).sorted().reduce((x, y) -> x + "," + y).orElse(""));
        item.setEnabledSnapshot(target.isEnabled());
        lenient().when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign(campaignId)));
        lenient().when(itemRepository.findByCampaignIdAndId(campaignId, itemId)).thenReturn(Optional.of(item));
        lenient().when(itemRepository.save(any(AccessReviewItem.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(appUserRepository.findById(target.getId())).thenReturn(Optional.of(target));
        return item;
    }

    @Test
    @DisplayName("recordDecision(CONFIRMED) does not touch the account")
    void recordDecision_confirmed_leavesAccountUntouched() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AppUser target = user("reviewed@test.local", AppUserRole.TRADER);
        item(campaignId, itemId, target);

        AccessReviewItem result = service.recordDecision(campaignId, itemId, AccessReviewDecision.CONFIRMED,
                "still needed", actorId, "REGISTRY_ADMIN");

        assertThat(result.getDecision()).isEqualTo(AccessReviewDecision.CONFIRMED);
        assertThat(result.getReviewedBy()).isEqualTo(actorId);
        verify(accountAccess, never()).disable(any(), any(), any(), any());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordDecision(REVOKED) disables the account through AccountAccessPort (last-admin/self guards)")
    void recordDecision_revoked_disablesAccount() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AppUser target = user("stale@test.local", AppUserRole.TRADER);
        item(campaignId, itemId, target);

        AccessReviewItem result = service.recordDecision(campaignId, itemId, AccessReviewDecision.REVOKED,
                "left the company", actorId, "REGISTRY_ADMIN");

        assertThat(result.getDecision()).isEqualTo(AccessReviewDecision.REVOKED);
        verify(accountAccess).disable(org.mockito.ArgumentMatchers.eq(target.getId()),
                org.mockito.ArgumentMatchers.eq(actorId), any(), any());
        verify(appUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("a refused disable (last admin) leaves the item undecided")
    void recordDecision_revoked_lastAdminGuardPropagates() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AppUser target = user("only@test.local", AppUserRole.TRADER);
        AccessReviewItem item = item(campaignId, itemId, target);
        org.mockito.Mockito.doThrow(new InvalidStateTransitionException("The system must keep at least one enabled REGISTRY_ADMIN"))
                .when(accountAccess).disable(any(), any(), any(), any());

        assertThatThrownBy(() -> service.recordDecision(campaignId, itemId, AccessReviewDecision.REVOKED,
                null, actorId, "REGISTRY_ADMIN")).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(item.getDecision()).isEqualTo(AccessReviewDecision.PENDING);
    }

    @Test
    @DisplayName("decisions are write-once: a decided item cannot be overwritten")
    void recordDecision_decidedItem_isRejected() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AccessReviewItem item = item(campaignId, itemId, user("x@test.local", AppUserRole.TRADER));
        item.setDecision(AccessReviewDecision.REVOKED);

        assertThatThrownBy(() -> service.recordDecision(campaignId, itemId, AccessReviewDecision.CONFIRMED,
                null, actorId, "REGISTRY_ADMIN")).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(item.getDecision()).isEqualTo(AccessReviewDecision.REVOKED);
    }

    @Test
    @DisplayName("REVOKED on a privileged account is only a proposal; a different second reviewer makes it effective")
    void recordDecision_privilegedRevocation_needsSecondReviewer() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AppUser target = user("admin2@test.local", AppUserRole.REGISTRY_ADMIN);
        AccessReviewItem item = item(campaignId, itemId, target);

        AccessReviewItem proposed = service.recordDecision(campaignId, itemId, AccessReviewDecision.REVOKED,
                "no longer needed", actorId, "COMPLIANCE_OFFICER");
        assertThat(proposed.getDecision()).isEqualTo(AccessReviewDecision.REVOKE_PROPOSED);
        assertThat(proposed.getProposedBy()).isEqualTo(actorId);
        verify(accountAccess, never()).disable(any(), any(), any(), any());

        // the proposer cannot confirm their own proposal
        assertThatThrownBy(() -> service.recordDecision(campaignId, itemId, AccessReviewDecision.REVOKED,
                null, actorId, "COMPLIANCE_OFFICER")).isInstanceOf(AccessDeniedException.class);

        UUID second = UUID.randomUUID();
        AccessReviewItem done = service.recordDecision(campaignId, itemId, AccessReviewDecision.REVOKED,
                "agreed", second, "REGISTRY_ADMIN");
        assertThat(done.getDecision()).isEqualTo(AccessReviewDecision.REVOKED);
        assertThat(done.getReviewedBy()).isEqualTo(second);
        verify(accountAccess).disable(org.mockito.ArgumentMatchers.eq(target.getId()),
                org.mockito.ArgumentMatchers.eq(second), any(), any());
    }

    @Test
    @DisplayName("a role granted after the snapshot makes the item STALE and the decision is refused")
    void recordDecision_driftedAccount_marksItemStale() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AppUser target = user("drift@test.local", AppUserRole.TRADER);
        AccessReviewItem item = item(campaignId, itemId, target);
        target.setRoles(Set.of(AppUserRole.TRADER, AppUserRole.COMPANY_ADMIN));

        assertThatThrownBy(() -> service.recordDecision(campaignId, itemId, AccessReviewDecision.CONFIRMED,
                null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("COMPANY_ADMIN");
        assertThat(item.getDecision()).isEqualTo(AccessReviewDecision.STALE);
    }

    @Test
    @DisplayName("the person who last changed the roles cannot review the account")
    void recordDecision_reviewerWhoChangedRoles_isRejected() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AppUser target = user("granted@test.local", AppUserRole.TRADER);
        target.markRolesChanged(actorId);
        item(campaignId, itemId, target);

        assertThatThrownBy(() -> service.recordDecision(campaignId, itemId, AccessReviewDecision.CONFIRMED,
                null, actorId, "REGISTRY_ADMIN")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("reopen re-snapshots the live account and returns the item to PENDING without enabling it")
    void reopen_resnapshots() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AppUser target = user("revoked@test.local", AppUserRole.TRADER);
        AccessReviewItem item = item(campaignId, itemId, target);
        item.setDecision(AccessReviewDecision.REVOKED);
        target.setEnabled(false);

        AccessReviewItem reopened = service.reopen(campaignId, itemId, "revoked by mistake", actorId, "REGISTRY_ADMIN");

        assertThat(reopened.getDecision()).isEqualTo(AccessReviewDecision.PENDING);
        assertThat(reopened.isEnabledSnapshot()).isFalse();
        assertThat(reopened.getReopenedCount()).isEqualTo(1);
        assertThat(target.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("startCampaign flags segregation-of-duties conflicts per item")
    void startCampaign_flagsSodConflicts() {
        when(campaignRepository.save(any(AccessReviewCampaign.class))).thenAnswer(inv -> {
            AccessReviewCampaign c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
        when(appUserRepository.findByEnabledTrueOrderByEmailAsc())
                .thenReturn(List.of(user("both@test.local", AppUserRole.REGISTRY_ADMIN, AppUserRole.COMPLIANCE_OFFICER)));
        when(itemRepository.save(any(AccessReviewItem.class))).thenAnswer(inv -> inv.getArgument(0));

        service.startCampaign("SoD", null, actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<AccessReviewItem> captor = ArgumentCaptor.forClass(AccessReviewItem.class);
        verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getSodConflicts()).isEqualTo("COMPLIANCE_OFFICER+REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("closeCampaign refuses while an enabled account created after the start is not in the campaign")
    void closeCampaign_uncoveredNewAccount_isRejected() {
        UUID campaignId = UUID.randomUUID();
        AccessReviewCampaign c = openCampaign(campaignId);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(c));
        when(itemRepository.findByCampaignIdOrderByEmailSnapshotAsc(campaignId)).thenReturn(List.of());
        when(appUserRepository.findEnabledCreatedOrRoleChangedSince(any()))
                .thenReturn(List.of(user("late@test.local", AppUserRole.REGISTRY_ADMIN)));

        assertThatThrownBy(() -> service.closeCampaign(campaignId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("late@test.local");
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("closeCampaign refuses when a confirmed account's roles changed since the snapshot")
    void closeCampaign_driftedConfirmedItem_isRejected() {
        UUID campaignId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        AppUser target = user("drift@test.local", AppUserRole.TRADER);
        AccessReviewItem item = item(campaignId, itemId, target);
        item.setDecision(AccessReviewDecision.CONFIRMED);
        when(itemRepository.findByCampaignIdOrderByEmailSnapshotAsc(campaignId)).thenReturn(List.of(item));
        target.setRoles(Set.of(AppUserRole.REGISTRY_ADMIN));

        assertThatThrownBy(() -> service.closeCampaign(campaignId, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STALE");
        assertThat(item.getDecision()).isEqualTo(AccessReviewDecision.STALE);
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
        when(itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.REVOKE_PROPOSED)).thenReturn(0L);

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
        when(itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.REVOKE_PROPOSED)).thenReturn(0L);
        when(itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.REVOKED)).thenReturn(1L);
        when(itemRepository.findByCampaignIdOrderByEmailSnapshotAsc(campaignId)).thenReturn(List.of());
        when(appUserRepository.findEnabledCreatedOrRoleChangedSince(any())).thenReturn(List.of());
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
