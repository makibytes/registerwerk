package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.customer.EntityHistoryService;
import de.makibytes.registerwerk.application.customer.LegalEntityService;
import de.makibytes.registerwerk.domain.entity.EntityMergeRecord;
import de.makibytes.registerwerk.domain.entity.EntityNameHistory;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.enums.EntityStatus;
import de.makibytes.registerwerk.domain.enums.EntityType;
import de.makibytes.registerwerk.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.web.dto.EntityResponse;
import de.makibytes.registerwerk.web.dto.EntityUpdateRequest;
import de.makibytes.registerwerk.web.dto.PageResponse;
import de.makibytes.registerwerk.web.mapper.EntityMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for legal entity CRUD and lifecycle operations.
 */
@RestController
@RequestMapping("/api/v1/entities")
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    private final LegalEntityService legalEntityService;
    private final EntityHistoryService entityHistoryService;
    private final EntityMapper entityMapper;

    public CustomerController(
            LegalEntityService legalEntityService,
            EntityHistoryService entityHistoryService,
            EntityMapper entityMapper) {
        this.legalEntityService = legalEntityService;
        this.entityHistoryService = entityHistoryService;
        this.entityMapper = entityMapper;
    }

    /**
     * Creates a new legal entity.
     */
    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<EntityResponse> createEntity(
            @RequestBody @Valid EntityCreateRequest request,
            Authentication auth) {
        UUID actorId = extractActorId(auth);
        LegalEntity entity = entityMapper.toEntity(request);
        LegalEntity created = legalEntityService.createEntity(entity, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(entityMapper.toResponse(created));
    }

    /**
     * Returns a paginated list of entities, with optional type and status filters.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
    public ResponseEntity<PageResponse<EntityResponse>> listEntities(
            @RequestParam(required = false) EntityType type,
            @RequestParam(required = false) EntityStatus status,
            Pageable pageable) {
        Page<LegalEntity> page = legalEntityService.listEntities(type, status, pageable);
        return ResponseEntity.ok(PageResponse.of(page.map(entityMapper::toResponse)));
    }

    /**
     * Returns a single entity by ID.
     * Accessible by admins, auditors, and the entity's own representative.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#id, authentication)")
    public ResponseEntity<EntityResponse> getEntity(@PathVariable UUID id) {
        LegalEntity entity = legalEntityService.getEntity(id);
        return ResponseEntity.ok(entityMapper.toResponse(entity));
    }

    /**
     * Updates a legal entity (full or partial — all fields optional).
     */
    @PutMapping("/{id}")
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<EntityResponse> updateEntity(
            @PathVariable UUID id,
            @RequestBody @Valid EntityUpdateRequest request) {
        LegalEntity patch = new LegalEntity();
        patch.setCurrentName(request.currentName());
        patch.setLeiCode(request.leiCode());
        patch.setRegistrationNumber(request.registrationNumber());
        patch.setRegistrationCountry(request.registrationCountry());
        patch.setIncorporationDate(request.incorporationDate());
        LegalEntity updated = legalEntityService.updateEntity(id, patch);
        return ResponseEntity.ok(entityMapper.toResponse(updated));
    }

    /**
     * Suspends a legal entity.
     */
    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> suspendEntity(@PathVariable UUID id, Authentication auth) {
        legalEntityService.suspendEntity(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Marks a legal entity as dissolved.
     */
    @PostMapping("/{id}/dissolve")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> dissolveEntity(@PathVariable UUID id, Authentication auth) {
        legalEntityService.dissolveEntity(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Reactivates a suspended entity.
     */
    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> reactivateEntity(@PathVariable UUID id, Authentication auth) {
        legalEntityService.reactivateEntity(id, extractActorId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns just the name change history for an entity.
     */
    @GetMapping("/{id}/name-history")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
    public ResponseEntity<List<EntityNameHistory>> getNameHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(entityHistoryService.listNameHistory(id));
    }

    /**
     * Returns the name history and merge records for an entity.
     */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
    public ResponseEntity<Map<String, Object>> getHistory(@PathVariable UUID id) {
        List<EntityNameHistory> nameHistory = entityHistoryService.listNameHistory(id);
        List<EntityMergeRecord> mergeRecords = entityHistoryService.listMergeRecords(id);
        return ResponseEntity.ok(Map.of("nameHistory", nameHistory, "mergeRecords", mergeRecords));
    }

    /**
     * Records an entity merge (M&A event).
     */
    @PostMapping("/{id}/merge")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<Void> mergeEntities(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> mergeRequest) {
        // TODO: Implement full merge workflow via a MergeService
        log.info("[STUB] mergeEntities called for id={}, request={}", id, mergeRequest);
        return ResponseEntity.accepted().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID extractActorId(Authentication auth) {
        if (auth == null) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
