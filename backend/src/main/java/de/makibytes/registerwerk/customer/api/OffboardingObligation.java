package de.makibytes.registerwerk.customer.api;

/**
 * One open obligation that makes terminating a customer a conscious decision (6-23). The
 * {@code id} is stable ({@code KIND:ref}) so an operator can acknowledge exactly this item; the
 * {@code kind} and {@code refId} become the follow-up {@link EntityTask} afterwards.
 */
public record OffboardingObligation(String kind, String refId, String description) {

    public String id() {
        return kind + ":" + refId;
    }
}
