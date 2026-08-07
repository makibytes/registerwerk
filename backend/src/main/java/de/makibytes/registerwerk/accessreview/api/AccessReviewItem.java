package de.makibytes.registerwerk.accessreview.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One account's entitlement snapshot within a campaign, and the reviewer's decision on it. */
@Entity
@Table(name = "access_review_item")
public class AccessReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "app_user_id", nullable = false)
    private UUID appUserId;

    @Column(name = "email_snapshot", nullable = false, length = 320)
    private String emailSnapshot;

    @Column(name = "full_name_snapshot", length = 200)
    private String fullNameSnapshot;

    /** Comma-separated role names at the moment the campaign was started — what's actually
     *  being attested to, independent of any role change the account undergoes mid-campaign. */
    @Column(name = "roles_snapshot", nullable = false, length = 500)
    private String rolesSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessReviewDecision decision = AccessReviewDecision.PENDING;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    public UUID getId() { return id; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID campaignId) { this.campaignId = campaignId; }
    public UUID getAppUserId() { return appUserId; }
    public void setAppUserId(UUID appUserId) { this.appUserId = appUserId; }
    public String getEmailSnapshot() { return emailSnapshot; }
    public void setEmailSnapshot(String emailSnapshot) { this.emailSnapshot = emailSnapshot; }
    public String getFullNameSnapshot() { return fullNameSnapshot; }
    public void setFullNameSnapshot(String fullNameSnapshot) { this.fullNameSnapshot = fullNameSnapshot; }
    public String getRolesSnapshot() { return rolesSnapshot; }
    public void setRolesSnapshot(String rolesSnapshot) { this.rolesSnapshot = rolesSnapshot; }
    public AccessReviewDecision getDecision() { return decision; }
    public void setDecision(AccessReviewDecision decision) { this.decision = decision; }
    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
