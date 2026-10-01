package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.customer.internal.EntityTaskService;
import de.makibytes.registerwerk.customer.web.dto.EntityTaskDtos.CompleteTaskRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityTaskDtos.EntityTaskResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Operator view of the entity lifecycle work items: termination follow-ups (issued securities
 * still live, Sperrvermerk holdings, open trades), re-KYC requests after risk-relevant changes
 * and chain-reinstatement requests after a reactivation.
 */
@RestController
@RequestMapping("/api/v1")
public class EntityTaskController {

    private final EntityTaskService tasks;

    EntityTaskController(EntityTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/entity-tasks")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT')")
    public ResponseEntity<List<EntityTaskResponse>> listOpen() {
        return ResponseEntity.ok(tasks.listOpen().stream().map(EntityTaskResponse::from).toList());
    }

    @GetMapping("/entities/{id}/tasks")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT')")
    public ResponseEntity<List<EntityTaskResponse>> listForEntity(@PathVariable UUID id) {
        return ResponseEntity.ok(tasks.listForEntity(id).stream().map(EntityTaskResponse::from).toList());
    }

    @PostMapping("/entity-tasks/{taskId}/done")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    public ResponseEntity<EntityTaskResponse> complete(@PathVariable UUID taskId,
                                                       @RequestBody @Valid CompleteTaskRequest request,
                                                       Authentication auth) {
        return ResponseEntity.ok(EntityTaskResponse.from(
                tasks.complete(taskId, SecurityUtils.extractUserId(auth), request.note())));
    }
}
