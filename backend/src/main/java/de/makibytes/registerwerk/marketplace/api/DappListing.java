package de.makibytes.registerwerk.marketplace.api;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A marketplace listing for a tokenization dApp. Metadata only — artifacts live in the
 * publisher's own OCI registry (pinned by digest in the manifest); the reviewed manifest's
 * keccak256 hash is anchored in the onchain DappRegistry on the listing's anchor chain.
 */
@Entity
@Table(name = "dapp_listing")
public class DappListing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "publisher_entity_id", nullable = false)
    private UUID publisherEntityId;

    /** Anchor chain for the onchain DappRegistry entry. */
    @Column(name = "chain_config_id", nullable = false)
    private UUID chainConfigId;

    @Column(name = "slug", nullable = false, unique = true, length = 60)
    private String slug;

    /** Onchain dappId = keccak256(slug). */
    @Column(name = "dapp_id_hash", nullable = false, length = 66)
    private String dappIdHash;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DappListingStatus status = DappListingStatus.DRAFT;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "docs_url", length = 500)
    private String docsUrl;

    /** Display-only pricing/licensing information; no billing machinery in v1. */
    @Column(name = "pricing_note", length = 500)
    private String pricingNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPublisherEntityId() { return publisherEntityId; }
    public void setPublisherEntityId(UUID publisherEntityId) { this.publisherEntityId = publisherEntityId; }

    public UUID getChainConfigId() { return chainConfigId; }
    public void setChainConfigId(UUID chainConfigId) { this.chainConfigId = chainConfigId; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getDappIdHash() { return dappIdHash; }
    public void setDappIdHash(String dappIdHash) { this.dappIdHash = dappIdHash; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public DappListingStatus getStatus() { return status; }
    public void setStatus(DappListingStatus status) { this.status = status; }

    public UUID getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(UUID currentVersionId) { this.currentVersionId = currentVersionId; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getDocsUrl() { return docsUrl; }
    public void setDocsUrl(String docsUrl) { this.docsUrl = docsUrl; }

    public String getPricingNote() { return pricingNote; }
    public void setPricingNote(String pricingNote) { this.pricingNote = pricingNote; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
