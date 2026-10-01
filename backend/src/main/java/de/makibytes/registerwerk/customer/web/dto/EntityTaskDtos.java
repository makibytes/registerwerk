package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.EntityTask;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class EntityTaskDtos {

    private EntityTaskDtos() {}

    public record EntityTaskResponse(UUID id, UUID entityId, String kind, String refId, String detail, String status,
                                     Instant createdAt, Instant doneAt, UUID doneBy, String doneNote) {
        public static EntityTaskResponse from(EntityTask t) {
            return new EntityTaskResponse(t.getId(), t.getEntityId(), t.getKind(), t.getRefId(), t.getDetail(),
                    t.getStatus().name(), t.getCreatedAt(), t.getDoneAt(), t.getDoneBy(), t.getDoneNote());
        }
    }

    public record CompleteTaskRequest(@NotBlank @Size(max = 2000) String note) {}
}
