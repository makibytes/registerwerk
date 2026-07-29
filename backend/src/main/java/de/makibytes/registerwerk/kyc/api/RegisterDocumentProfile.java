package de.makibytes.registerwerk.kyc.api;

/**
 * Cross-jurisdiction register-document labeling for eWpG-family register extracts and
 * their labeled analogues. See {@link JurisdictionRequirementConfig#resolveRegisterDocumentProfile}
 * for the resolution rules.
 *
 * <p>Only Germany's § 19 eWpG Registerauszug (individual entry / Einzeleintragung) and
 * France's attestation d'inscription en compte (Art. L211-3 et seq. Code monétaire et
 * financier) are genuine statutory register-extract regimes ({@code statutory = true}).
 * Luxembourg and Liechtenstein confirmations, and every collectively-held (nominee /
 * Sammeleintragung) position regardless of jurisdiction, are labeled analogues or holding
 * confirmations — deliberately never mislabeled as the German statutory document — and
 * carry a {@code disclaimerNote} explaining the distinction and, where the exact
 * regulatory equivalence has not been confirmed, recommending local-counsel review.
 */
public record RegisterDocumentProfile(
        String docType,
        String title,
        String legalBasisLine,
        String disclaimerNote,
        boolean statutory
) {}
