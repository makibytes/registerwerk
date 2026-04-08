package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.domain.audit.AuditEvent;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AuditEventRepository;
import de.makibytes.registerwerk.web.dto.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for searching and retrieving audit trail events.
 */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditEventRepository auditEventRepository;

    public AuditController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * Searches audit events with optional filters.
     *
     * @param subjectType filter by subject type (e.g. "Asset", "LegalEntity")
     * @param subjectId   filter by subject ID
     * @param eventType   filter by event type (e.g. "ASSET_ISSUED")
     * @param actorId     filter by actor ID
     */
    @GetMapping("/events")
    public ResponseEntity<PageResponse<AuditEvent>> searchEvents(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID actorId,
            Pageable pageable) {

        Page<AuditEvent> page;

        if (subjectType != null && subjectId != null) {
            page = auditEventRepository.findBySubjectTypeAndSubjectId(subjectType, subjectId, pageable);
        } else if (eventType != null) {
            page = auditEventRepository.findByEventType(eventType, pageable);
        } else if (actorId != null) {
            page = auditEventRepository.findByActorId(actorId, pageable);
        } else {
            page = auditEventRepository.findAll(pageable);
        }

        return ResponseEntity.ok(PageResponse.of(page));
    }

    /**
     * Returns a single audit event by ID.
     */
    @GetMapping("/events/{id}")
    public ResponseEntity<AuditEvent> getEvent(@PathVariable UUID id) {
        AuditEvent event = auditEventRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("AuditEvent", id));
        return ResponseEntity.ok(event);
    }
}
