package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.ClientCategory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code reason} is mandatory (and a second approver is required) when the new category is less
 * protective than the current one; {@code evidenceDocumentId} references the evidence on file.
 */
public record ClassifyClientRequest(
        @NotNull ClientCategory clientCategory,
        @Size(max = 2000) String reason,
        UUID evidenceDocumentId) {

    public ClassifyClientRequest(ClientCategory clientCategory) {
        this(clientCategory, null, null);
    }
}
