package de.makibytes.registerwerk.customer.web.dto;

import java.util.UUID;

/** {@code relationshipManagerId} null clears the assignment. */
public record AssignRelationshipManagerRequest(UUID relationshipManagerId) {}
