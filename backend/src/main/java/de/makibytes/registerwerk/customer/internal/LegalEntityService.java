package de.makibytes.registerwerk.customer.internal;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.customer.events.EntityCreatedEvent;
import de.makibytes.registerwerk.customer.events.EntityUpdatedEvent;
import de.makibytes.registerwerk.customer.events.EntitySuspendedEvent;
import de.makibytes.registerwerk.customer.events.EntityReactivatedEvent;
import de.makibytes.registerwerk.customer.events.EntityReinstatementRequestedEvent;
import de.makibytes.registerwerk.customer.events.EntityRenamedEvent;
import de.makibytes.registerwerk.customer.events.EntityMergedEvent;
import de.makibytes.registerwerk.customer.events.ClientClassifiedEvent;
import de.makibytes.registerwerk.customer.events.RelationshipManagerAssignedEvent;
import de.makibytes.registerwerk.customer.events.EntityRiskDataChangedEvent;
import de.makibytes.registerwerk.customer.events.SuitabilityAssessmentRecordedEvent;
import de.makibytes.registerwerk.customer.api.EntityReactivationGuard;
import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.stepup.api.DualControlGate;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.EntityMergeRecord;
import de.makibytes.registerwerk.customer.api.EntityMergeRecordRepository;
import de.makibytes.registerwerk.customer.api.EntityNameHistory;
import de.makibytes.registerwerk.customer.api.KnowledgeExperienceLevel;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.EntityNameHistoryRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.customer.api.RiskTolerance;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessment;
import de.makibytes.registerwerk.customer.api.SuitabilityAssessmentRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service responsible for managing legal entity lifecycle.
 */
@Service
@Transactional
public class LegalEntityService {

    private static final Logger log = LoggerFactory.getLogger(LegalEntityService.class);

    private final LegalEntityRepository legalEntityRepository;
    private final EntityNameHistoryRepository entityNameHistoryRepository;
    private final EntityMergeRecordRepository entityMergeRecordRepository;
    private final SuitabilityAssessmentRepository suitabilityAssessmentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityNumberGenerator entityNumberGenerator;
    private final AppUserRepository appUserRepository;
    private final CustomerOffboardingService offboardingService;
    private final EntityTaskPort taskPort;
    private final DualControlGate dualControlGate;
    private final List<EntityReactivationGuard> reactivationGuards;

    public LegalEntityService(
            LegalEntityRepository legalEntityRepository,
            EntityNameHistoryRepository entityNameHistoryRepository,
            EntityMergeRecordRepository entityMergeRecordRepository,
            SuitabilityAssessmentRepository suitabilityAssessmentRepository,
            ApplicationEventPublisher eventPublisher,
            EntityNumberGenerator entityNumberGenerator,
            AppUserRepository appUserRepository,
            CustomerOffboardingService offboardingService,
            EntityTaskPort taskPort,
            DualControlGate dualControlGate,
            List<EntityReactivationGuard> reactivationGuards) {
        this.legalEntityRepository = legalEntityRepository;
        this.entityNameHistoryRepository = entityNameHistoryRepository;
        this.entityMergeRecordRepository = entityMergeRecordRepository;
        this.suitabilityAssessmentRepository = suitabilityAssessmentRepository;
        this.eventPublisher = eventPublisher;
        this.entityNumberGenerator = entityNumberGenerator;
        this.appUserRepository = appUserRepository;
        this.offboardingService = offboardingService;
        this.taskPort = taskPort;
        this.dualControlGate = dualControlGate;
        this.reactivationGuards = reactivationGuards == null ? List.of() : reactivationGuards;
    }

    /**
     * Creates a new legal entity, assigns an entity number, and publishes an audit event.
     */
    public LegalEntity createEntity(LegalEntity entity, UUID createdBy) {
        entity.setEntityNumber(entityNumberGenerator.generateEntityNumber());
        entity.setCreatedBy(createdBy);
        LegalEntity saved = legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new EntityCreatedEvent(saved.getId(), createdBy, null, java.util.Map.of("entityNumber", saved.getEntityNumber(), "name", saved.getCurrentName())));
        log.info("Created legal entity: id={}, number={}", saved.getId(), saved.getEntityNumber());
        return saved;
    }

    /**
     * Retrieves a legal entity by ID.
     *
     * @throws EntityNotFoundException if not found
     */
    @Transactional(readOnly = true)
    public LegalEntity getEntity(UUID id) {
        return legalEntityRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", id));
    }

    /** Upper bound for the free-text {@code search} term of {@link #listEntities}. */
    static final int MAX_SEARCH_LENGTH = 200;

    /**
     * Returns a filtered, paginated list of entities. Every filter is optional and they combine
     * with AND; {@code search} (trimmed) matches the legal name case-insensitively anywhere and the
     * entity number case-insensitively as a prefix. LIKE wildcards in the term are literals.
     *
     * @throws IllegalArgumentException if {@code search} is longer than {@value #MAX_SEARCH_LENGTH}
     *                                  characters (mapped to 400)
     */
    @Transactional(readOnly = true)
    public Page<LegalEntity> listEntities(EntityType type, EntityStatus status, KycStatus kycStatus,
                                          String search, Pageable pageable) {
        String term = search == null ? "" : search.trim();
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search must be at most " + MAX_SEARCH_LENGTH + " characters");
        }
        return legalEntityRepository.findAll(entityFilter(type, status, kycStatus, term), pageable);
    }

    private static Specification<LegalEntity> entityFilter(EntityType type, EntityStatus status,
                                                           KycStatus kycStatus, String term) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (kycStatus != null) {
                predicates.add(cb.equal(root.get("kycStatus"), kycStatus));
            }
            if (!term.isEmpty()) {
                String escaped = term.toLowerCase(java.util.Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("currentName")), "%" + escaped + "%", '\\'),
                        cb.like(cb.lower(root.get("entityNumber")), escaped + "%", '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Master-data fields whose change is risk relevant: re-screen and (for APPROVED entities) re-KYC. */
    private static final List<String> RISK_FIELDS = List.of("currentName", "leiCode", "registrationCountry");

    /**
     * Applies non-null fields from {@code patch} to the stored entity and emits an audit event
     * carrying before/after of every changed field. A change of name, LEI or country of
     * registration triggers a re-screening ({@code ENTITY_DATA_CHANGED}, run by the screening
     * module after commit) and, for an APPROVED entity, a {@code KYC_REVIEW_REQUIRED} task; the KYC
     * status itself is not changed automatically (parked decision T6-14).
     */
    public LegalEntity updateEntity(UUID id, LegalEntity patch, UUID actorId) {
        LegalEntity existing = getEntity(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (patch.getCurrentName() != null) {
            recordChange(changes, "currentName", existing.getCurrentName(), patch.getCurrentName());
            existing.setCurrentName(patch.getCurrentName());
        }
        if (patch.getLeiCode() != null) {
            recordChange(changes, "leiCode", existing.getLeiCode(), patch.getLeiCode());
            existing.setLeiCode(patch.getLeiCode());
        }
        if (patch.getRegistrationNumber() != null) {
            recordChange(changes, "registrationNumber", existing.getRegistrationNumber(), patch.getRegistrationNumber());
            existing.setRegistrationNumber(patch.getRegistrationNumber());
        }
        if (patch.getRegistrationCountry() != null) {
            recordChange(changes, "registrationCountry", existing.getRegistrationCountry(), patch.getRegistrationCountry());
            existing.setRegistrationCountry(patch.getRegistrationCountry());
        }
        if (patch.getIncorporationDate() != null) {
            recordChange(changes, "incorporationDate", existing.getIncorporationDate(), patch.getIncorporationDate());
            existing.setIncorporationDate(patch.getIncorporationDate());
        }
        LegalEntity saved = legalEntityRepository.save(existing);
        eventPublisher.publishEvent(new EntityUpdatedEvent(id, actorId, null, Map.of("changes", changes)));
        onRiskDataChanged(saved, actorId, changes.keySet().stream().filter(RISK_FIELDS::contains).toList());
        log.info("Updated entity: id={} changed={}", id, changes.keySet());
        return saved;
    }

    private static void recordChange(Map<String, Object> changes, String field, Object before, Object after) {
        if (java.util.Objects.equals(before, after)) {
            return;
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("old", before == null ? null : before.toString());
        change.put("new", after.toString());
        changes.put(field, change);
    }

    /** Re-screening trigger plus the re-KYC task for an APPROVED entity; no-op for an empty list. */
    private void onRiskDataChanged(LegalEntity entity, UUID actorId, List<String> riskFields) {
        if (riskFields.isEmpty()) {
            return;
        }
        eventPublisher.publishEvent(new EntityRiskDataChangedEvent(entity.getId(), actorId, riskFields));
        if (entity.getKycStatus() == KycStatus.APPROVED) {
            taskPort.open(entity.getId(), EntityTask.KYC_REVIEW_REQUIRED, "ENTITY_DATA_CHANGED",
                    "Risk-relevant master data changed (" + String.join(", ", riskFields)
                            + "); review the KYC file. The KYC status was not changed automatically.", actorId);
        }
    }

    private LegalEntity transition(UUID id, EntityStatus target) {
        LegalEntity entity = getEntity(id);
        if (!entity.getStatus().canTransitionTo(target)) {
            throw new InvalidStateTransitionException("LegalEntity", entity.getStatus().name(), target.name());
        }
        entity.setStatus(target);
        return legalEntityRepository.save(entity);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
    }

    /**
     * Suspends an ACTIVE entity (reversible). The controller requires step-up and a second
     * approver; the reason is persisted on the audit event. Chain-side effect: the org is
     * suspended on every chain (orgidentity listens to {@link EntitySuspendedEvent}).
     */
    public void suspendEntity(UUID id, UUID actorId, String reason) {
        requireReason(reason);
        LegalEntity before = getEntity(id);
        EntityStatus from = before.getStatus();
        transition(id, EntityStatus.SUSPENDED);
        eventPublisher.publishEvent(new EntitySuspendedEvent(id, actorId, null,
                Map.of("reason", reason, "from", from.name(), "to", EntityStatus.SUSPENDED.name())));
        log.info("Suspended entity: id={}", id);
    }

    /**
     * Reactivates a SUSPENDED entity. CLOSED/DISSOLVED are terminal and PENDING_ONBOARDING can
     * only be activated by onboarding. Refused while KYC is EXPIRED/REJECTED, a screening hit is
     * unresolved or a Sperrvermerk is active ({@link EntityReactivationGuard}). The on-chain
     * org is NOT reinstated automatically: an operator task {@code CHAIN_REINSTATEMENT_REQUIRED}
     * is raised for the 4-eyes reinstatement.
     */
    public void reactivateEntity(UUID id, UUID actorId, String reason) {
        requireReason(reason);
        LegalEntity entity = getEntity(id);
        if (entity.getStatus() != EntityStatus.SUSPENDED) {
            throw new InvalidStateTransitionException("LegalEntity", entity.getStatus().name(), "REACTIVATED");
        }
        List<String> blockers = new ArrayList<>();
        if (entity.getKycStatus() == KycStatus.EXPIRED || entity.getKycStatus() == KycStatus.REJECTED) {
            blockers.add("KYC status is " + entity.getKycStatus());
        }
        for (EntityReactivationGuard guard : reactivationGuards) {
            blockers.addAll(guard.blockers(id));
        }
        if (!blockers.isEmpty()) {
            throw new ComplianceGateException("Entity " + id + " cannot be reactivated: " + String.join("; ", blockers));
        }
        transition(id, EntityStatus.ACTIVE);
        eventPublisher.publishEvent(new EntityReactivatedEvent(id, actorId, null,
                Map.of("reason", reason, "from", EntityStatus.SUSPENDED.name(), "to", EntityStatus.ACTIVE.name())));
        eventPublisher.publishEvent(new EntityRiskDataChangedEvent(id, actorId, List.of("REACTIVATION")));
        log.info("Reactivated entity: id={}", id);
    }

    /**
     * Starts the reinstatement of a CLOSED/DISSOLVED entity (T6-12): -&gt; PENDING_REACTIVATION,
     * never directly ACTIVE. Needs a reason and a legal reference (the controller requires step-up
     * and a second approver). Effects: KYC is reset (a fresh APPROVED KYC through the normal
     * approve flow and its gates is required; it then activates the entity), a mandatory
     * re-screening is triggered, operator tasks are raised and an audited event is emitted. The
     * entity stays blocked like any non-ACTIVE entity; on-chain claims and org membership are not
     * touched until the KYC is approved again (the existing reactivation listener then raises the
     * {@code CHAIN_REINSTATEMENT_REQUIRED} task for the explicit 4-eyes chain reinstatement).
     */
    public void requestReinstatement(UUID id, UUID actorId, String reason, String legalReference) {
        requireReason(reason);
        if (legalReference == null || legalReference.isBlank()) {
            throw new IllegalArgumentException("A legal reference is required");
        }
        LegalEntity entity = getEntity(id);
        EntityStatus from = entity.getStatus();
        if (!from.isTerminal()) {
            throw new InvalidStateTransitionException("LegalEntity", from.name(), EntityStatus.PENDING_REACTIVATION.name());
        }
        KycStatus previousKyc = entity.getKycStatus();
        entity.setKycStatus(KycStatus.NOT_STARTED);
        entity.setKycExpiryDate(null);
        transition(id, EntityStatus.PENDING_REACTIVATION);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("legalReference", legalReference.trim());
        details.put("from", from.name());
        details.put("to", EntityStatus.PENDING_REACTIVATION.name());
        details.put("previousKycStatus", previousKyc.name());
        eventPublisher.publishEvent(new EntityReinstatementRequestedEvent(id, actorId, null, details));
        taskPort.open(id, EntityTask.REINSTATEMENT_KYC_REQUIRED, "",
                "Reinstatement requested (" + legalReference.trim() + "): re-screening was triggered and KYC was reset; "
                        + "complete a fresh KYC approval (checklist, beneficial owners, screening) to reactivate the entity.", actorId);
        taskPort.open(id, EntityTask.REINSTATEMENT_USERS_REVIEW, "",
                "Users were disabled at termination; re-enable or re-invite the customer's users once KYC is approved.", actorId);
        // Mandatory re-screening: the screening module re-screens on this event; no clear result keeps KYC approval blocked.
        eventPublisher.publishEvent(new EntityRiskDataChangedEvent(id, actorId, List.of("REINSTATEMENT")));
        log.warn("Reinstatement requested: entity={} from={} legalReference={}", id, from, legalReference);
    }

    /**
     * Records a name change in the entity's name history and updates the current name.
     */
    public void renameEntity(UUID id, String newName, LocalDate effectiveDate, UUID actorId) {
        LegalEntity entity = getEntity(id);
        String previousName = entity.getCurrentName();

        EntityNameHistory history = new EntityNameHistory();
        history.setLegalEntityId(id);
        history.setPreviousName(previousName);
        history.setNewName(newName);
        history.setChangeType(EntityNameHistory.ChangeType.RENAME);
        history.setEffectiveDate(effectiveDate);
        history.setRecordedBy(actorId);
        entityNameHistoryRepository.save(history);

        entity.setCurrentName(newName);
        legalEntityRepository.save(entity);

        eventPublisher.publishEvent(new EntityRenamedEvent(id, actorId, null, java.util.Map.of("previousName", previousName, "newName", newName, "effectiveDate", effectiveDate.toString())));
        if (!newName.equals(previousName)) {
            onRiskDataChanged(entity, actorId, List.of("currentName"));
        }
        log.info("Renamed entity: id={}, from='{}' to='{}'", id, previousName, newName);
    }

    /**
     * Records an entity merge (M&A event): the source entity is absorbed into (or consolidated
     * with) the target entity and marked {@link EntityStatus#DISSOLVED} - it ceases independent
     * legal existence but its record, name history, and audit trail are retained (German
     * commercial law retention requirements). DISSOLVED is reachable only here.
     *
     * <p>The source goes through the same off-ramp as a termination ({@link
     * CustomerOffboardingService#dissolveByMerger}: users disabled, sessions and tokens revoked,
     * {@code CustomerOffboardedEvent} for the other modules, open obligations recorded as
     * follow-up tasks - a merger transfers them, it does not acknowledge them away). The target
     * is re-screened and gets a {@code KYC_REVIEW_REQUIRED} task. The controller requires step-up
     * and a second approver; {@code reason} (and optionally the evidence document id) are
     * recorded on the event.
     */
    public EntityMergeRecord mergeEntities(UUID sourceEntityId, UUID targetEntityId,
                                            EntityMergeRecord.MergeType mergeType,
                                            LocalDate effectiveDate, String notes, UUID actorId,
                                            String reason, UUID evidenceDocumentId) {
        if (sourceEntityId.equals(targetEntityId)) {
            throw new IllegalArgumentException("An entity cannot be merged into itself.");
        }
        requireReason(reason);
        LegalEntity source = getEntity(sourceEntityId);
        LegalEntity target = getEntity(targetEntityId);
        if (!source.getStatus().canTransitionTo(EntityStatus.DISSOLVED)) {
            throw new InvalidStateTransitionException(
                    "Source entity " + sourceEntityId + " is " + source.getStatus() + " and cannot be merged.");
        }
        if (target.getStatus().isTerminal() || target.getStatus() == EntityStatus.PENDING_REACTIVATION) {
            throw new InvalidStateTransitionException(
                    "Target entity " + targetEntityId + " is " + target.getStatus() + " and cannot absorb another entity.");
        }

        EntityStatus sourceFrom = source.getStatus();
        EntityMergeRecord record = new EntityMergeRecord();
        record.setSourceEntityId(sourceEntityId);
        record.setTargetEntityId(targetEntityId);
        record.setMergeType(mergeType);
        record.setEffectiveDate(effectiveDate);
        record.setNotes(notes);
        record.setRecordedBy(actorId);
        EntityMergeRecord saved = entityMergeRecordRepository.save(record);

        offboardingService.dissolveByMerger(sourceEntityId, targetEntityId, actorId, "REGISTRY_ADMIN", reason);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetEntityId", targetEntityId.toString());
        details.put("mergeType", mergeType.name());
        details.put("effectiveDate", effectiveDate.toString());
        details.put("reason", reason);
        details.put("from", sourceFrom.name());
        details.put("to", EntityStatus.DISSOLVED.name());
        if (evidenceDocumentId != null) details.put("evidenceDocumentId", evidenceDocumentId.toString());
        eventPublisher.publishEvent(new EntityMergedEvent(sourceEntityId, actorId, null, details));

        taskPort.open(targetEntityId, EntityTask.KYC_REVIEW_REQUIRED, "MERGER:" + sourceEntityId,
                "Absorbed entity " + sourceEntityId + " (" + mergeType + "); review the KYC file and beneficial owners.",
                actorId);
        eventPublisher.publishEvent(new EntityRiskDataChangedEvent(targetEntityId, actorId, List.of("MERGER")));
        log.info("Merged entity: source={} into target={} type={}", sourceEntityId, targetEntityId, mergeType);
        return saved;
    }

    /**
     * MiFID II client classification (Annex II) - set by the firm (REGISTRY_ADMIN /
     * COMPLIANCE_OFFICER at the controller boundary), never self-declared by the client.
     * Moving to a less protective category (RETAIL -&gt; PROFESSIONAL -&gt; ELIGIBLE_COUNTERPARTY)
     * gates the repo desk and lending, so it needs a reason and a second approver
     * ({@link DualControlGate}); it also raises a {@code KYC_REVIEW_REQUIRED} task. The audit event
     * carries the previous category, the reason and the optional evidence document id.
     */
    public LegalEntity classifyClient(UUID id, ClientCategory category, UUID actorId,
                                      String reason, UUID evidenceDocumentId) {
        LegalEntity entity = getEntity(id);
        ClientCategory previous = entity.getClientCategory() == null ? ClientCategory.RETAIL : entity.getClientCategory();
        boolean lessProtective = category.ordinal() > previous.ordinal();
        if (lessProtective) {
            requireReason(reason);
            dualControlGate.require("CLIENT_CLASSIFICATION_DOWNGRADE");
        }
        entity.setClientCategory(category);
        entity.setClientCategoryClassifiedAt(Instant.now());
        entity.setClientCategoryClassifiedBy(actorId);
        LegalEntity saved = legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new ClientClassifiedEvent(id, actorId, null, category.name(),
                previous.name(), reason, evidenceDocumentId));
        if (lessProtective && saved.getKycStatus() == KycStatus.APPROVED) {
            taskPort.open(id, EntityTask.KYC_REVIEW_REQUIRED, "CLIENT_CATEGORY",
                    "Client category lowered " + previous + " -> " + category + "; confirm the evidence on file.", actorId);
        }
        log.info("Classified entity: id={} category={} (was {})", id, category, previous);
        return saved;
    }

    /**
     * Records a new suitability assessment. Immutable/append-only (see
     * {@link SuitabilityAssessment}'s Javadoc) — a reassessment is a new row, never an edit of
     * the previous one.
     */
    public SuitabilityAssessment recordSuitabilityAssessment(
            UUID entityId, KnowledgeExperienceLevel knowledgeExperience, RiskTolerance riskTolerance,
            Integer investmentHorizonYears, boolean financialSituationAdequate, String notes, UUID actorId) {
        getEntity(entityId); // 404s if the entity doesn't exist
        SuitabilityAssessment assessment = new SuitabilityAssessment();
        assessment.setEntityId(entityId);
        assessment.setKnowledgeExperience(knowledgeExperience);
        assessment.setRiskTolerance(riskTolerance);
        assessment.setInvestmentHorizonYears(investmentHorizonYears);
        assessment.setFinancialSituationAdequate(financialSituationAdequate);
        assessment.setNotes(notes);
        assessment.setAssessedBy(actorId);
        SuitabilityAssessment saved = suitabilityAssessmentRepository.save(assessment);
        eventPublisher.publishEvent(new SuitabilityAssessmentRecordedEvent(
                entityId, actorId, null, saved.getId(), knowledgeExperience.name(), riskTolerance.name()));
        log.info("Recorded suitability assessment: entityId={} id={}", entityId, saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SuitabilityAssessment> listSuitabilityAssessments(UUID entityId) {
        return suitabilityAssessmentRepository.findByEntityIdOrderByAssessedAtDesc(entityId);
    }

    @Transactional(readOnly = true)
    public Optional<SuitabilityAssessment> getLatestSuitabilityAssessment(UUID entityId) {
        return suitabilityAssessmentRepository.findFirstByEntityIdOrderByAssessedAtDesc(entityId);
    }

    /**
     * Assigns (or reassigns, or clears with {@code relationshipManagerId=null}) the client-servicing
     * relationship manager for this entity (F-BLOCKER-15).
     */
    public LegalEntity assignRelationshipManager(UUID entityId, UUID relationshipManagerId, UUID actorId) {
        LegalEntity entity = getEntity(entityId);
        if (relationshipManagerId != null) {
            AppUser manager = appUserRepository.findById(relationshipManagerId)
                    .orElseThrow(() -> new EntityNotFoundException("AppUser", relationshipManagerId));
            if (!manager.isEnabled() || !manager.getRoles().contains(AppUserRole.RELATIONSHIP_MANAGER)) {
                throw new IllegalArgumentException(
                        "Assigned user must be enabled and have the RELATIONSHIP_MANAGER role");
            }
        }
        entity.setAssignedRelationshipManagerId(relationshipManagerId);
        LegalEntity saved = legalEntityRepository.save(entity);
        eventPublisher.publishEvent(new RelationshipManagerAssignedEvent(entityId, actorId, null, relationshipManagerId));
        log.info("Assigned relationship manager: entityId={} rmId={}", entityId, relationshipManagerId);
        return saved;
    }

    /** Entities currently assigned to a given relationship manager — the "my clients" list. */
    @Transactional(readOnly = true)
    public List<LegalEntity> listAssignedToRelationshipManager(UUID relationshipManagerId) {
        return legalEntityRepository.findByAssignedRelationshipManagerId(relationshipManagerId);
    }
}
