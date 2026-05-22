package de.makibytes.registerwerk.erc3643.internal;

/**
 * Standard ERC-3643 claim topics.
 * Topic numbers are globally standardised by the ERC-3643 specification.
 * Custom topics may be used above 100.
 */
public enum ClaimTopic {

    KYC(1L, "KYC"),
    AML(2L, "AML"),
    ACCREDITATION(3L, "ACCREDITATION");

    private final long topicId;
    private final String label;

    ClaimTopic(long topicId, String label) {
        this.topicId = topicId;
        this.label = label;
    }

    public long getTopicId() { return topicId; }
    public String getLabel() { return label; }

    public static ClaimTopic fromId(long id) {
        for (ClaimTopic t : values()) {
            if (t.topicId == id) return t;
        }
        return null;
    }
}
