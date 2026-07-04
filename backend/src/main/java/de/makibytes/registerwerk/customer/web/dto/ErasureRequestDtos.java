package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.ErasureRequest;

import java.time.Instant;
import java.util.UUID;

/** DTOs for the operator-facing DSGVO Art. 17 erasure queue. */
public final class ErasureRequestDtos {

    private ErasureRequestDtos() {}

    public record ErasureRequestResponse(
            UUID id,
            UUID entityId,
            UUID requestedByUserId,
            String status,
            Instant requestedAt,
            Instant dueAt,
            UUID reviewedBy,
            Instant reviewedAt,
            String resolutionNote) {

        public static ErasureRequestResponse from(ErasureRequest r) {
            return new ErasureRequestResponse(
                    r.getId(), r.getEntityId(), r.getRequestedByUserId(), r.getStatus().name(),
                    r.getRequestedAt(), r.getDueAt(), r.getReviewedBy(), r.getReviewedAt(), r.getResolutionNote());
        }
    }

    /** Operator's resolution note for completing/rejecting a request. */
    public record ResolveErasureRequest(String note) {}
}
