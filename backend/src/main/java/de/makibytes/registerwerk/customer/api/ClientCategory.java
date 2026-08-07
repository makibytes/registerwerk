package de.makibytes.registerwerk.customer.api;

/** MiFID II client categorisation (Annex II) — determines both the conduct-of-business
 *  protections a {@link LegalEntity} is owed and which assets it may be distributed
 *  (see {@code Asset.getTargetMarketCategories()}). Set by the firm, not self-declared. */
public enum ClientCategory {
    RETAIL,
    PROFESSIONAL,
    ELIGIBLE_COUNTERPARTY
}
