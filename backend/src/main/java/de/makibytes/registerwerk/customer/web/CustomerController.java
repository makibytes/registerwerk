package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.customer.internal.CustomerOffboardingService;
import de.makibytes.registerwerk.customer.internal.EntityHistoryService;
import de.makibytes.registerwerk.customer.internal.LegalEntityService;
import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import de.makibytes.registerwerk.customer.api.EntityNameHistory;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import de.makibytes.registerwerk.customer.web.dto.EntityUpdateRequest;
import de.makibytes.registerwerk.customer.web.dto.MergeEntityRequest;
import de.makibytes.registerwerk.customer.web.dto.TerminateEntityRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.api.PageResponse;
import de.makibytes.registerwerk.customer.web.EntityMapper;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
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
    private final CustomerOffboardingService offboardingService;

    public CustomerController(
            LegalEntityService legalEntityService,
            EntityHistoryService entityHistoryService,
            EntityMapper entityMapper,
            CustomerOffboardingService offboardingService) {
        this.legalEntityService = legalEntityService;
        this.entityHistoryService = entityHistoryService;
        this.entityMapper = entityMapper;
        this.offboardingService = offboardingService;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created, auth));
    }

    /**
     * Returns a paginated list of entities, with optional type and status filters.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
    public ResponseEntity<PageResponse<EntityResponse>> listEntities(
            @RequestParam(required = false) EntityType type,
            @RequestParam(required = false) EntityStatus status,
            Authentication auth,
            Pageable pageable) {
        Page<LegalEntity> page = legalEntityService.listEntities(type, status, pageable);
        return ResponseEntity.ok(PageResponse.of(page.map(entity -> toResponse(entity, auth))));
    }

    /**
     * Returns a single entity by ID.
     * Accessible by admins, auditors, and the entity's own representative.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#id, authentication)")
    public ResponseEntity<EntityResponse> getEntity(@PathVariable UUID id, Authentication auth) {
        LegalEntity entity = legalEntityService.getEntity(id);
        return ResponseEntity.ok(toResponse(entity, auth));
    }

    /**
     * Updates a legal entity (full or partial — all fields optional).
     */
    @RequestMapping(path = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<EntityResponse> updateEntity(
            @PathVariable UUID id,
            @RequestBody @Valid EntityUpdateRequest request,
            Authentication auth) {
        LegalEntity patch = new LegalEntity();
        patch.setCurrentName(request.currentName());
        patch.setLeiCode(request.leiCode());
        patch.setRegistrationNumber(request.registrationNumber());
        patch.setRegistrationCountry(request.registrationCountry());
        patch.setIncorporationDate(request.incorporationDate());
        LegalEntity updated = legalEntityService.updateEntity(id, patch, extractActorId(auth));
        return ResponseEntity.ok(toResponse(updated, auth));
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
     * Terminates the customer relationship — the off-ramp: disables the entity's users,
     * publishes {@code CustomerOffboardedEvent} so other modules cancel open trade listings,
     * revoke ASSET_TOKEN_ADMIN grants, and raise portfolio-migration requests for every
     * holding/issuance needing operator follow-up, and moves the entity to CLOSED. Irreversible
     * enough to warrant the same step-up + dual-control bar as a forced transfer.
     */
    @PostMapping("/{id}/terminate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "CUSTOMER_OFFBOARDING")
    public ResponseEntity<EntityResponse> terminateEntity(@PathVariable UUID id,
                                                           @Valid @RequestBody TerminateEntityRequest request,
                                                           Authentication auth) {
        LegalEntity terminated = offboardingService.terminate(id, extractActorId(auth),
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), request.reason());
        return ResponseEntity.ok(toResponse(terminated, auth));
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
     * Records an entity merge (M&A event): the entity at {@code id} is absorbed into
     * {@code request.targetEntityId()} and marked dissolved.
     */
    @PostMapping("/{id}/merge")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<EntityMergeRecord> mergeEntities(
            @PathVariable UUID id,
            @RequestBody @Valid MergeEntityRequest request,
            Authentication auth) {
        EntityMergeRecord record = legalEntityService.mergeEntities(
                id, request.targetEntityId(), request.mergeType(),
                request.effectiveDate(), request.notes(), extractActorId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(record);
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

    private EntityResponse toResponse(LegalEntity entity, Authentication authentication) {
        EntityResponse base = entityMapper.toResponse(entity);
        return new EntityResponse(
                base.id(),
                base.entityNumber(),
                base.type(),
                base.status(),
                base.currentName(),
                base.leiCode(),
                base.registrationNumber(),
                base.kycStatus(),
                base.createdAt(),
                null
        );
    }
}
