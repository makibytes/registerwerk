package de.makibytes.registerwerk.customer.api;

/**
 * Lifecycle of a DSGVO Art. 17 (right to erasure) request. Erasure is never automatic:
 * the operator must weigh each request against statutory retention (eWpG §15(3): 10y,
 * GwG §8: 5y) before erasing any non-mandatory field.
 */
public enum ErasureRequestStatus {
    /** Submitted by the data subject, awaiting operator triage. */
    REQUESTED,
    /** An operator has picked it up and is reviewing which fields may be erased. */
    IN_REVIEW,
    /** Erasure of the erasable fields was carried out (or nothing was erasable). */
    COMPLETED,
    /** Erasure was declined in full, e.g. all fields fall under a retention obligation. */
    REJECTED
}
