package de.makibytes.registerwerk.stepup.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The request an initiator wants a second person to approve. {@code action} is the {@code @RequiresStepUp} reason
 * of the target endpoint; {@code path} and {@code query} are exactly what the real request will carry (no host,
 * no context path, no leading '?'), {@code body} its JSON body (omit it for a request without one, and for the
 * few reasons whose payload is not JSON or is secret).
 */
public record CreateApprovalRequest(
        @NotBlank @Size(max = 160) String action,
        @NotBlank @Size(max = 8) String method,
        @NotBlank @Size(max = 500) String path,
        @Size(max = 2000) String query,
        @JsonDeserialize(using = ExactJsonNodeDeserializer.class) JsonNode body) {}
