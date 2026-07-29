package de.makibytes.registerwerk.registerstatement.web.dto;

import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.deployment.api.EntryType;

import java.util.UUID;

/**
 * Metadata for one downloadable register document, so the frontend can render the
 * correct label ("Registerauszug", "Bestandsbestätigung", "Attestation…", …) and
 * distinguish a genuine statutory extract from a labeled analogue / holding
 * confirmation before the investor downloads it.
 */
public record RegisterDocumentMetaResponse(
        UUID assetId,
        String isin,
        String assetName,
        Jurisdiction jurisdiction,
        EntryType entryType,
        String docType,
        String title,
        boolean statutory
) {}
