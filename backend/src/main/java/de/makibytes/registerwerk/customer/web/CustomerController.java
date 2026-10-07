package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.customer.internal.CustomerOffboardingService;
import de.makibytes.registerwerk.customer.internal.EntityHistoryService;
import de.makibytes.registerwerk.customer.internal.LegalEntityService;
import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import de.makibytes.registerwerk.customer.api.EntityNameHistory;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.web.dto.EntityCreateRequest;
import de.makibytes.registerwerk.customer.web.dto.EntityResponse;
import de.makibytes.registerwerk.customer.web.dto.EntityUpdateRequest;
import de.makibytes.registerwerk.customer.web.dto.MergeEntityRequest;
import de.makibytes.registerwerk.customer.web.dto.LifecycleReasonRequest;
import de.makibytes.registerwerk.customer.web.dto.ReinstatementRequest;
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
     * Returns a paginated list of entities, with optional type, status, KYC-status and free-text
     * search filters (legal name contains / entity number prefix; at most 200 characters).
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT', 'SUPPORT_AGENT')")
    public ResponseEntity<PageResponse<EntityResponse>> listEntities(
            @RequestParam(required = false) EntityType type,
            @RequestParam(required = false) EntityStatus status,
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) String search,
            Authentication auth,
            Pageable pageable) {
        Page<LegalEntity> page = legalEntityService.listEntities(type, status, kycStatus, search, pageable);
        return ResponseEntity.ok(PageResponse.of(page.map(entity -> toResponse(entity, auth))));
    }

    /**
     * "My clients" — the entities currently assigned to the caller as relationship manager
     * (F-BLOCKER-15). Not paginated: an RM's book is expected to be a bounded, human-sized list,
     * unlike the operator-wide {@link #listEntities}.
     */
    @GetMapping("/my-clients")
    @PreAuthorize("hasRole('RELATIONSHIP_MANAGER')")
    public ResponseEntity<List<EntityResponse>> myClients(Authentication auth) {
        UUID rmId = extractActorId(auth);
        List<EntityResponse> clients = legalEntityService.listAssignedToRelationshipManager(rmId).stream()
                .map(entity -> toResponse(entity, auth))
                .toList();
        return ResponseEntity.ok(clients);
    }

    /**
     * Assigns (or, with a null body field, clears) the client-servicing relationship manager for
     * this entity.
     */
    @PostMapping("/{id}/relationship-manager")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    public ResponseEntity<EntityResponse> assignRelationshipManager(
            @PathVariable UUID id,
            @RequestBody @Valid de.makibytes.registerwerk.customer.web.dto.AssignRelationshipManagerRequest request,
            Authentication auth) {
        LegalEntity updated = legalEntityService.assignRelationshipManager(id, request.relationshipManagerId(), extractActorId(auth));
        return ResponseEntity.ok(toResponse(updated, auth));
    }

    /**
     * Returns a single entity by ID.
     * Accessible by admins, auditors, the entity's own representative, and its assigned
     * relationship manager (read-only — F-BLOCKER-15).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT') or @entityOwnershipChecker.isOwner(#id, authentication) "
            + "or @entityOwnershipChecker.isAssignedRelationshipManager(#id, authentication)")
    public ResponseEntity<EntityResponse> getEntity(@PathVariable UUID id, Authentication auth) {
        LegalEntity entity = legalEntityService.getEntity(id);
        return ResponseEntity.ok(toResponse(entity, auth));
    }

    /**
     * Updates a legal entity (full or partial — all fields optional).
     */
    @RequestMapping(path = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "ENTITY_MASTER_DATA_CHANGE")
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
     * Suspends an ACTIVE legal entity (reversible; the on-chain org is suspended too). Step-up and
     * a second approver; the reason is mandatory and persisted.
     */
    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "ENTITY_SUSPEND")
    public ResponseEntity<Void> suspendEntity(@PathVariable UUID id,
                                              @RequestBody @Valid LifecycleReasonRequest request,
                                              Authentication auth) {
        legalEntityService.suspendEntity(id, extractActorId(auth), request.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Reactivates a SUSPENDED entity only (CLOSED/DISSOLVED are terminal, PENDING_ONBOARDING is
     * activated by onboarding). Step-up, second approver and a reason; refused while KYC is
     * EXPIRED/REJECTED, a screening hit is unresolved or a Sperrvermerk is active. The on-chain org
     * stays suspended until an operator completes the {@code CHAIN_REINSTATEMENT_REQUIRED} task.
     */
    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "ENTITY_REACTIVATE")
    public ResponseEntity<Void> reactivateEntity(@PathVariable UUID id,
                                                 @RequestBody @Valid LifecycleReasonRequest request,
                                                 Authentication auth) {
        legalEntityService.reactivateEntity(id, extractActorId(auth), request.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Starts the reinstatement of a CLOSED/DISSOLVED entity (T6-12): -&gt; PENDING_REACTIVATION, never
     * straight to ACTIVE. Step-up, second approver, mandatory reason and legal reference. The entity
     * stays blocked until a fresh KYC approval; re-screening, tasks and audit are handled by the
     * service; on-chain state is untouched until then.
     */
    @PostMapping("/{id}/reinstate")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "ENTITY_REINSTATE")
    public ResponseEntity<Void> reinstateEntity(@PathVariable UUID id,
                                                @RequestBody @Valid ReinstatementRequest request,
                                                Authentication auth) {
        legalEntityService.requestReinstatement(id, extractActorId(auth), request.reason(), request.legalReference());
        return ResponseEntity.noContent().build();
    }

    /**
     * The open obligations that {@link #terminateEntity} requires an acknowledgement for. (The
     * former {@code POST /{id}/dissolve} is gone: DISSOLVED is reached only through a merger, an
     * exit goes through terminate.)
     */
    @GetMapping("/{id}/offboarding-obligations")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<List<Map<String, String>>> offboardingObligations(@PathVariable UUID id) {
        legalEntityService.getEntity(id);
        List<Map<String, String>> body = offboardingService.openObligations(id).stream()
                .map(o -> Map.of("obligationId", o.id(), "kind", o.kind(), "refId", o.refId(),
                        "description", o.description()))
                .toList();
        return ResponseEntity.ok(body);
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
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), request.reason(), request.acknowledgements());
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
    @RequiresStepUp(requireSecondApprover = true, reason = "ENTITY_MERGE")
    public ResponseEntity<EntityMergeRecord> mergeEntities(
            @PathVariable UUID id,
            @RequestBody @Valid MergeEntityRequest request,
            Authentication auth) {
        EntityMergeRecord record = legalEntityService.mergeEntities(
                id, request.targetEntityId(), request.mergeType(),
                request.effectiveDate(), request.notes(), extractActorId(auth),
                request.reason(), request.evidenceDocumentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(record);
    }

    /**
     * Sets the entity's MiFID II client category (Annex II) — the firm classifies the client,
     * never self-declared (see {@code LegalEntityService.classifyClient} Javadoc).
     */
    @PostMapping("/{id}/classification")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    @RequiresStepUp(reason = "CLIENT_CLASSIFICATION")
    public ResponseEntity<EntityResponse> classifyClient(
            @PathVariable UUID id,
            @RequestBody @Valid de.makibytes.registerwerk.customer.web.dto.ClassifyClientRequest request,
            Authentication auth) {
        LegalEntity updated = legalEntityService.classifyClient(id, request.clientCategory(), extractActorId(auth),
                request.reason(), request.evidenceDocumentId());
        return ResponseEntity.ok(toResponse(updated, auth));
    }

    /**
     * Records a new MiFID suitability assessment for the entity.
     */
    @PostMapping("/{id}/suitability-assessments")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
    public ResponseEntity<de.makibytes.registerwerk.customer.web.dto.SuitabilityAssessmentResponse> recordSuitabilityAssessment(
            @PathVariable UUID id,
            @RequestBody @Valid de.makibytes.registerwerk.customer.web.dto.SuitabilityAssessmentRequest request,
            Authentication auth) {
        var assessment = legalEntityService.recordSuitabilityAssessment(
                id, request.knowledgeExperience(), request.riskTolerance(), request.investmentHorizonYears(),
                request.financialSituationAdequate(), request.notes(), extractActorId(auth));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(de.makibytes.registerwerk.customer.web.dto.SuitabilityAssessmentResponse.from(assessment));
    }

    /**
     * Lists an entity's suitability assessment history (most recent first) — includes the owning
     * entity itself so an investor can see their own assessment record, matching {@link #getEntity}.
     */
    @GetMapping("/{id}/suitability-assessments")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT', 'COMPLIANCE_OFFICER') or @entityOwnershipChecker.isOwner(#id, authentication)")
    public ResponseEntity<List<de.makibytes.registerwerk.customer.web.dto.SuitabilityAssessmentResponse>> listSuitabilityAssessments(
            @PathVariable UUID id) {
        var assessments = legalEntityService.listSuitabilityAssessments(id).stream()
                .map(de.makibytes.registerwerk.customer.web.dto.SuitabilityAssessmentResponse::from)
                .toList();
        return ResponseEntity.ok(assessments);
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
                null,
                base.clientCategory(),
                base.clientCategoryClassifiedAt(),
                base.assignedRelationshipManagerId()
        );
    }
}
