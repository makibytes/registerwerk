package de.makibytes.registerwerk.domain.erc3643;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Represents a required claim topic registered in a T-REX suite's ClaimTopicsRegistry.
 * All investors must hold a valid claim for every topic listed here before transfers are allowed.
 */
@Entity
@Table(name = "erc3643_claim_topic")
public class Erc3643ClaimTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The ERC-3643 suite this topic belongs to. */
    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    /** ERC-3643 claim topic number (e.g. 1 = KYC, 2 = AML, 3 = ACCREDITATION). */
    @Column(name = "topic", nullable = false)
    private long topic;

    /** Human-readable label for this topic, e.g. "KYC", "AML". */
    @Column(name = "label", nullable = false, length = 100)
    private String label;

    // ── Getters & Setters ──────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSuiteId() { return suiteId; }
    public void setSuiteId(UUID suiteId) { this.suiteId = suiteId; }

    public long getTopic() { return topic; }
    public void setTopic(long topic) { this.topic = topic; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
