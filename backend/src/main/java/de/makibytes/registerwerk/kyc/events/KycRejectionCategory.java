package de.makibytes.registerwerk.kyc.events;

/**
 * The only rejection information that leaves the platform (webhooks, e-mail). Deliberately coarse so
 * a rejection never reveals an internal finding (GwG s.47 tipping-off). Final customer-facing wording
 * and whether e-mails may carry a category at all is parked decision T5-14.
 */
public enum KycRejectionCategory {
    INFORMATION_INCOMPLETE,
    DOCUMENTS_UNREADABLE,
    INFORMATION_INCONSISTENT,
    CONTACT_SUPPORT
}
