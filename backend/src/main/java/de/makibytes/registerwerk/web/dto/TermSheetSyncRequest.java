package de.makibytes.registerwerk.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for triggering an on-chain term sheet sync for a specific deployment.
 */
public record TermSheetSyncRequest(@NotNull UUID deploymentId) {}
