package de.makibytes.registerwerk.accessreview.internal;

import de.makibytes.registerwerk.accessreview.api.AccessReviewCampaign;
import de.makibytes.registerwerk.accessreview.api.AccessReviewCampaignRepository;
import de.makibytes.registerwerk.accessreview.api.AccessReviewDecision;
import de.makibytes.registerwerk.accessreview.api.AccessReviewItem;
import de.makibytes.registerwerk.accessreview.api.AccessReviewItemRepository;
import de.makibytes.registerwerk.accessreview.api.AccessReviewStatus;
import de.makibytes.registerwerk.accessreview.events.AccessReviewCampaignClosedEvent;
import de.makibytes.registerwerk.accessreview.events.AccessReviewCampaignStartedEvent;
import de.makibytes.registerwerk.accessreview.events.AccessReviewDecisionRecordedEvent;
import de.makibytes.registerwerk.accessreview.events.AccessReviewItemReopenedEvent;
import de.makibytes.registerwerk.auth.api.AccountAccessPort;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Access recertification (entitlement review) campaigns — BAIT (and every bank's IAM policy)
 * requires periodic review and sign-off of user entitlements.
 *
 * <p>A REVOKED decision has real effect — it disables the account through
 * {@link AccountAccessPort}, which applies the last-admin and self guards of the user-management
 * screens, ends live sessions and burns unconsumed registration / reset tokens — so a completed
 * campaign is a genuine control, not an audit-trail exercise with no teeth.
 *
 * <p>Controls (Phase 6, 6-03): decisions are write-once (a correction is an explicit
 * {@link #reopen}); revoking a privileged account (REGISTRY_ADMIN, COMPLIANCE_OFFICER,
 * COMPANY_ADMIN) is campaign-native four-eyes (REVOKE_PROPOSED, then a second reviewer); the
 * snapshot is compared with the live account at decision and at close (drift makes the item
 * STALE); the reviewer must not be the person who last changed the reviewee's roles; segregation
 * of duties pairs are shown as warnings (enforcement parked: T6-15).
 */
@Service
public class AccessReviewService {

    private static final Logger log = LoggerFactory.getLogger(AccessReviewService.class);

    /** Roles whose revocation needs a second reviewer. */
    private static final Set<AppUserRole> PRIVILEGED = Set.of(
            AppUserRole.REGISTRY_ADMIN, AppUserRole.COMPLIANCE_OFFICER, AppUserRole.COMPANY_ADMIN,
            AppUserRole.SUPPORT_AGENT);

    private final AccessReviewCampaignRepository campaignRepository;
    private final AccessReviewItemRepository itemRepository;
    private final AppUserRepository appUserRepository;
    private final ApplicationEventPublisher events;
    private final AccountAccessPort accountAccess;
    private final List<Set<String>> sodPairs;

    public AccessReviewService(AccessReviewCampaignRepository campaignRepository,
                                AccessReviewItemRepository itemRepository,
                                AppUserRepository appUserRepository,
                                ApplicationEventPublisher events,
                                AccountAccessPort accountAccess,
                                @Value("${registerwerk.access-review.sod-conflicts:REGISTRY_ADMIN+COMPLIANCE_OFFICER}")
                                List<String> sodConflicts) {
        this.campaignRepository = campaignRepository;
        this.itemRepository = itemRepository;
        this.appUserRepository = appUserRepository;
        this.events = events;
        this.accountAccess = accountAccess;
        this.sodPairs = sodConflicts.stream()
                .map(String::trim).filter(x -> !x.isEmpty())
                .map(x -> (Set<String>) new HashSet<>(List.of(x.split("\\+"))))
                .toList();
    }

    @Transactional
    public AccessReviewCampaign startCampaign(String name, LocalDate dueDate, UUID actorId, String actorRole) {
        AccessReviewCampaign campaign = new AccessReviewCampaign();
        campaign.setName(name);
        campaign.setDueDate(dueDate);
        campaign.setStartedBy(actorId);
        AccessReviewCampaign saved = campaignRepository.save(campaign);

        List<AppUser> users = appUserRepository.findByEnabledTrueOrderByEmailAsc();
        for (AppUser user : users) {
            AccessReviewItem item = new AccessReviewItem();
            item.setCampaignId(saved.getId());
            item.setAppUserId(user.getId());
            item.setEmailSnapshot(user.getEmail());
            item.setFullNameSnapshot(user.getFullName());
            snapshot(item, user);
            itemRepository.save(item);
        }

        log.info("Access review campaign started: id={} name={} items={}", saved.getId(), name, users.size());
        events.publishEvent(new AccessReviewCampaignStartedEvent(saved.getId(), actorId, actorRole,
                Map.of("name", name, "itemCount", users.size())));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AccessReviewCampaign> listCampaigns() {
        return campaignRepository.findAllByOrderByStartedAtDesc();
    }

    @Transactional(readOnly = true)
    public AccessReviewCampaign getCampaign(UUID campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new EntityNotFoundException("AccessReviewCampaign", campaignId));
    }

    @Transactional(readOnly = true)
    public List<AccessReviewItem> listItems(UUID campaignId) {
        return itemRepository.findByCampaignIdOrderByEmailSnapshotAsc(campaignId);
    }

    /**
     * Records a reviewer's decision on one item (CONFIRMED or REVOKED).
     *
     * <ul>
     *   <li>Write-once: only a PENDING item can be decided; a REVOKE_PROPOSED item only accepts
     *       REVOKED from a reviewer other than the proposer.</li>
     *   <li>Never your own access, and never for an account whose roles you changed last.</li>
     *   <li>If the live account no longer matches the snapshot the item becomes STALE (persisted
     *       although the request is refused) and must be re-opened.</li>
     *   <li>REVOKED on a privileged account is first only a proposal.</li>
     * </ul>
     */
    @Transactional(noRollbackFor = InvalidStateTransitionException.class)
    public AccessReviewItem recordDecision(UUID campaignId, UUID itemId, AccessReviewDecision decision,
                                            String notes, UUID actorId, String actorRole) {
        if (decision != AccessReviewDecision.CONFIRMED && decision != AccessReviewDecision.REVOKED) {
            throw new IllegalArgumentException("Decision must be CONFIRMED or REVOKED");
        }
        AccessReviewCampaign campaign = getCampaign(campaignId);
        if (campaign.getStatus() != AccessReviewStatus.OPEN) {
            throw new IllegalStateException("Campaign " + campaignId + " is already closed");
        }
        AccessReviewItem item = itemRepository.findByCampaignIdAndId(campaignId, itemId)
                .orElseThrow(() -> new EntityNotFoundException("AccessReviewItem", itemId));
        if (item.getAppUserId().equals(actorId)) {
            throw new AccessDeniedException("Cannot review your own access — ask another reviewer.");
        }

        AccessReviewDecision current = item.getDecision();
        boolean secondReviewer = current == AccessReviewDecision.REVOKE_PROPOSED;
        if (current == AccessReviewDecision.STALE) {
            throw new InvalidStateTransitionException(
                    "Item is STALE: the account changed after the snapshot. Re-open it before deciding.");
        }
        if (current != AccessReviewDecision.PENDING && !secondReviewer) {
            throw new InvalidStateTransitionException(
                    "Item already decided (" + current + "). Decisions are write-once; re-open the item to correct it.");
        }
        if (secondReviewer) {
            if (decision != AccessReviewDecision.REVOKED) {
                throw new InvalidStateTransitionException(
                        "A revoke proposal is open on this item: confirm it with REVOKED (second reviewer) or re-open the item.");
            }
            if (actorId != null && actorId.equals(item.getProposedBy())) {
                throw new AccessDeniedException("The revocation of a privileged account needs a second, different reviewer.");
            }
        }

        AppUser live = appUserRepository.findById(item.getAppUserId()).orElse(null);
        if (live == null) {
            if (decision != AccessReviewDecision.REVOKED) {
                throw new InvalidStateTransitionException("The account no longer exists; record REVOKED to close the item.");
            }
        } else {
            if (actorId != null && actorId.equals(live.getRolesChangedBy())) {
                throw new AccessDeniedException(
                        "You last changed this account's roles; another reviewer must review it (reviewer independence).");
            }
            if (drifted(item, live)) {
                item.setDecision(AccessReviewDecision.STALE);
                itemRepository.save(item);
                throw new InvalidStateTransitionException("The account changed after the campaign snapshot (snapshot roles="
                        + item.getRolesSnapshot() + " enabled=" + item.isEnabledSnapshot() + "; live roles="
                        + rolesToString(live.getRoles()) + " enabled=" + live.isEnabled()
                        + "). Item marked STALE: re-open and review it again.");
            }
        }

        boolean privileged = isPrivileged(item.getRolesSnapshot());
        boolean effective;
        String recorded;
        if (decision == AccessReviewDecision.REVOKED && privileged && !secondReviewer) {
            item.setDecision(AccessReviewDecision.REVOKE_PROPOSED);
            item.setProposedBy(actorId);
            item.setProposedAt(Instant.now());
            item.setNotes(notes);
            effective = false;
            recorded = AccessReviewDecision.REVOKE_PROPOSED.name();
        } else {
            if (decision == AccessReviewDecision.REVOKED && live != null) {
                accountAccess.disable(item.getAppUserId(), actorId, actorRole,
                        "access review " + campaignId + " item " + itemId + (notes == null ? "" : ": " + notes));
                log.warn("Access review revoked entitlements for user={} email={} (campaign={})",
                        live.getId(), live.getEmail(), campaignId);
            }
            item.setDecision(decision);
            item.setNotes(secondReviewer && notes == null ? item.getNotes() : notes);
            item.setReviewedBy(actorId);
            item.setReviewedAt(Instant.now());
            effective = true;
            recorded = decision.name();
        }
        AccessReviewItem saved = itemRepository.save(item);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("campaignId", campaignId.toString());
        payload.put("appUserId", saved.getAppUserId().toString());
        payload.put("decision", recorded);
        payload.put("requestedDecision", decision.name());
        payload.put("effective", effective);
        payload.put("email", saved.getEmailSnapshot());
        payload.put("snapshotRoles", saved.getRolesSnapshot());
        payload.put("liveRoles", live == null ? "" : rolesToString(live.getRoles()));
        payload.put("sodConflicts", saved.getSodConflicts() == null ? "" : saved.getSodConflicts());
        payload.put("proposedBy", saved.getProposedBy() == null ? "" : saved.getProposedBy().toString());
        events.publishEvent(new AccessReviewDecisionRecordedEvent(saved.getId(), actorId, actorRole, payload));
        return saved;
    }

    /**
     * Returns an already decided (or STALE) item to PENDING with a fresh snapshot of the live
     * account. The only way to correct a decision. A previously revoked account stays disabled:
     * re-confirming it is an attestation, re-enabling it goes through user management.
     */
    @Transactional
    public AccessReviewItem reopen(UUID campaignId, UUID itemId, String reason, UUID actorId, String actorRole) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to re-open an item");
        }
        AccessReviewCampaign campaign = getCampaign(campaignId);
        if (campaign.getStatus() != AccessReviewStatus.OPEN) {
            throw new IllegalStateException("Campaign " + campaignId + " is already closed");
        }
        AccessReviewItem item = itemRepository.findByCampaignIdAndId(campaignId, itemId)
                .orElseThrow(() -> new EntityNotFoundException("AccessReviewItem", itemId));
        if (item.getAppUserId().equals(actorId)) {
            throw new AccessDeniedException("Cannot re-open the review of your own access.");
        }
        if (item.getDecision() == AccessReviewDecision.PENDING) {
            throw new InvalidStateTransitionException("Item is already PENDING");
        }
        AppUser live = appUserRepository.findById(item.getAppUserId())
                .orElseThrow(() -> new EntityNotFoundException("AppUser", item.getAppUserId()));
        AccessReviewDecision previous = item.getDecision();
        String previousRoles = item.getRolesSnapshot();

        snapshot(item, live);
        item.setDecision(AccessReviewDecision.PENDING);
        item.setReviewedBy(null);
        item.setReviewedAt(null);
        item.setProposedBy(null);
        item.setProposedAt(null);
        item.setReopenedCount(item.getReopenedCount() + 1);
        AccessReviewItem saved = itemRepository.save(item);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("campaignId", campaignId.toString());
        payload.put("appUserId", saved.getAppUserId().toString());
        payload.put("previousDecision", previous.name());
        payload.put("previousRoles", previousRoles);
        payload.put("snapshotRoles", saved.getRolesSnapshot());
        payload.put("enabled", live.isEnabled());
        payload.put("reason", reason);
        events.publishEvent(new AccessReviewItemReopenedEvent(saved.getId(), actorId, actorRole, payload));
        return saved;
    }

    /**
     * Only closeable once every item has a final decision, no item is STALE, and no account that
     * a campaign started at its start time could not contain (created or role-changed since) is
     * enabled — otherwise closing would attest a review that did not cover the live population.
     */
    @Transactional(noRollbackFor = IllegalStateException.class)
    public AccessReviewCampaign closeCampaign(UUID campaignId, UUID actorId, String actorRole) {
        AccessReviewCampaign campaign = getCampaign(campaignId);
        if (campaign.getStatus() != AccessReviewStatus.OPEN) {
            throw new IllegalStateException("Campaign " + campaignId + " is already closed");
        }
        long pending = itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.PENDING)
                + itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.REVOKE_PROPOSED);
        if (pending > 0) {
            throw new IllegalStateException(pending + " item(s) still awaiting a decision");
        }

        List<AccessReviewItem> items = itemRepository.findByCampaignIdOrderByEmailSnapshotAsc(campaignId);
        List<String> stale = new ArrayList<>();
        Set<UUID> covered = new HashSet<>();
        for (AccessReviewItem item : items) {
            covered.add(item.getAppUserId());
            if (item.getDecision() == AccessReviewDecision.STALE) {
                stale.add(item.getEmailSnapshot());
            } else if (item.getDecision() == AccessReviewDecision.CONFIRMED) {
                AppUser live = appUserRepository.findById(item.getAppUserId()).orElse(null);
                if (live == null || drifted(item, live)) {
                    item.setDecision(AccessReviewDecision.STALE);
                    itemRepository.save(item);
                    stale.add(item.getEmailSnapshot());
                }
            }
        }
        if (!stale.isEmpty()) {
            throw new IllegalStateException(stale.size() + " item(s) are STALE (account changed since the snapshot): "
                    + String.join(", ", stale) + ". Re-open and re-review them.");
        }
        List<String> uncovered = appUserRepository.findEnabledCreatedOrRoleChangedSince(campaign.getStartedAt()).stream()
                .filter(u -> !covered.contains(u.getId()))
                .map(AppUser::getEmail)
                .toList();
        if (!uncovered.isEmpty()) {
            throw new IllegalStateException(uncovered.size() + " enabled account(s) were created or changed after the "
                    + "campaign started and are not part of it: " + String.join(", ", uncovered)
                    + ". Start a new campaign to cover them.");
        }

        campaign.setStatus(AccessReviewStatus.CLOSED);
        campaign.setClosedBy(actorId);
        campaign.setClosedAt(Instant.now());
        AccessReviewCampaign saved = campaignRepository.save(campaign);

        long revoked = itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.REVOKED);
        log.info("Access review campaign closed: id={} revoked={}", campaignId, revoked);
        events.publishEvent(new AccessReviewCampaignClosedEvent(saved.getId(), actorId, actorRole,
                Map.of("revokedCount", revoked)));
        return saved;
    }

    private void snapshot(AccessReviewItem item, AppUser user) {
        item.setRolesSnapshot(rolesToString(user.getRoles()));
        item.setEnabledSnapshot(user.isEnabled());
        item.setEmailSnapshot(user.getEmail());
        item.setFullNameSnapshot(user.getFullName());
        String conflicts = sodPairs.stream()
                .filter(pair -> pair.stream().allMatch(r -> user.getRoles().stream().anyMatch(x -> x.name().equals(r))))
                .map(pair -> String.join("+", new java.util.TreeSet<>(pair)))
                .reduce((a, b) -> a + "," + b).orElse(null);
        item.setSodConflicts(conflicts);
    }

    private static boolean drifted(AccessReviewItem item, AppUser live) {
        return !item.getRolesSnapshot().equals(rolesToString(live.getRoles()))
                || item.isEnabledSnapshot() != live.isEnabled();
    }

    private static boolean isPrivileged(String rolesSnapshot) {
        if (rolesSnapshot == null || rolesSnapshot.isEmpty()) return false;
        for (String r : rolesSnapshot.split(",")) {
            for (AppUserRole p : PRIVILEGED) {
                if (p.name().equals(r)) return true;
            }
        }
        return false;
    }

    private static String rolesToString(Set<AppUserRole> roles) {
        return roles.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }
}
