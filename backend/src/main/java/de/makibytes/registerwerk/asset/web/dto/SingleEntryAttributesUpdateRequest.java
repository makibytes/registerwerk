package de.makibytes.registerwerk.asset.web.dto;

/**
 * Request payload to update a single-entry holder's §17(2) eWpG attributes on
 * the instruction of an authorised party (§18(1)). All fields are optional;
 * only non-null fields are applied.
 */
public record SingleEntryAttributesUpdateRequest(
    Boolean isConsumer,
    String thirdPartyRights,
    String disposalRestrictions,
    String legalCapacityNote
) {}
