package de.makibytes.registerwerk.shared.web;

import java.time.Instant;
import java.util.UUID;

/**
 * Compliance status for a single required document type within a jurisdiction.
 */
public record DocumentStatusResponse(
    String documentType,
    boolean mandatory,
    String localName,
    String description,
    boolean present,
    boolean expired,
    boolean tooOld,
    Instant documentDate,
    UUID documentId
) {}
