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
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Access recertification (entitlement review) campaigns — BAIT (and every bank's IAM policy)
 * requires periodic review and sign-off of user entitlements. Previously there was no campaign
 * tooling, no attestation record, and no way to tell how long ago (if ever) an account's roles
 * were last reviewed.
 *
 * <p>A REVOKED decision has real effect — it disables the account, not just records a finding —
 * so a completed campaign is a genuine control, not an audit-trail exercise with no teeth.
 */
@Service
public class AccessReviewService {

    private static final Logger log = LoggerFactory.getLogger(AccessReviewService.class);

    private final AccessReviewCampaignRepository campaignRepository;
    private final AccessReviewItemRepository itemRepository;
    private final AppUserRepository appUserRepository;
    private final ApplicationEventPublisher events;

    public AccessReviewService(AccessReviewCampaignRepository campaignRepository,
                                AccessReviewItemRepository itemRepository,
                                AppUserRepository appUserRepository,
                                ApplicationEventPublisher events) {
        this.campaignRepository = campaignRepository;
        this.itemRepository = itemRepository;
        this.appUserRepository = appUserRepository;
        this.events = events;
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
            item.setRolesSnapshot(rolesToString(user.getRoles()));
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
     * Records a reviewer's decision on one item. A CONFIRMED decision is purely an attestation;
     * a REVOKED decision also disables the account — the same lever
     * {@code OperatorUserService.setEnabled}/{@code CompanyUserService.setEnabled} use, applied
     * directly here since a campaign spans both operator and customer accounts and this module
     * has no business reaching into either module's internals for it.
     *
     * <p>A reviewer cannot decide on their own item — self-attestation defeats the point of a
     * review, the same segregation-of-duties principle already enforced for dual-control
     * approvals elsewhere in this codebase.
     */
    @Transactional
    public AccessReviewItem recordDecision(UUID campaignId, UUID itemId, AccessReviewDecision decision,
                                            String notes, UUID actorId, String actorRole) {
        if (decision == AccessReviewDecision.PENDING) {
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

        item.setDecision(decision);
        item.setNotes(notes);
        item.setReviewedBy(actorId);
        item.setReviewedAt(Instant.now());
        AccessReviewItem saved = itemRepository.save(item);

        if (decision == AccessReviewDecision.REVOKED) {
            appUserRepository.findById(item.getAppUserId()).ifPresent(user -> {
                user.setEnabled(false);
                appUserRepository.save(user);
                log.warn("Access review revoked entitlements for user={} email={} (campaign={})",
                        user.getId(), user.getEmail(), campaignId);
            });
        }

        events.publishEvent(new AccessReviewDecisionRecordedEvent(saved.getId(), actorId, actorRole,
                Map.of("campaignId", campaignId.toString(), "appUserId", saved.getAppUserId().toString(),
                        "decision", decision.name(), "email", saved.getEmailSnapshot())));
        return saved;
    }

    /** Only closeable once every item has a decision — an open PENDING item is an unreviewed
     *  account, and closing the campaign around it would be a false attestation that the review
     *  happened. */
    @Transactional
    public AccessReviewCampaign closeCampaign(UUID campaignId, UUID actorId, String actorRole) {
        AccessReviewCampaign campaign = getCampaign(campaignId);
        if (campaign.getStatus() != AccessReviewStatus.OPEN) {
            throw new IllegalStateException("Campaign " + campaignId + " is already closed");
        }
        long pending = itemRepository.countByCampaignIdAndDecision(campaignId, AccessReviewDecision.PENDING);
        if (pending > 0) {
            throw new IllegalStateException(pending + " item(s) still awaiting a decision");
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

    private static String rolesToString(Set<AppUserRole> roles) {
        return roles.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }
}
