package de.makibytes.registerwerk.registertransfer.api;

/**
 * Asserted basis for a §10 eWpG register inspection request.
 *
 * <p>ISSUER, HOLDER and BENEFICIARY are Berechtigte who, per §10(2) eWpRV,
 * always have a legitimate interest and are entitled to inspect. LEGITIMATE_INTEREST
 * covers any other applicant and requires operator review of the stated interest.
 */
public enum InspectionLegalBasis {
    ISSUER,
    HOLDER,
    BENEFICIARY,
    LEGITIMATE_INTEREST
}
