package de.makibytes.registerwerk.screening.web;

import de.makibytes.registerwerk.screening.internal.ScreeningHit;
import de.makibytes.registerwerk.screening.internal.ScreeningHitRepository;
import de.makibytes.registerwerk.screening.internal.ScreeningRun;
import de.makibytes.registerwerk.screening.internal.ScreeningRunRepository;
import de.makibytes.registerwerk.screening.internal.ScreeningService;
import de.makibytes.registerwerk.screening.api.ScreeningTrigger;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API for sanctions / PEP screening management.
 * Accessible to COMPLIANCE_OFFICER and REGISTRY_ADMIN roles.
 * GwG §10, MiCAR Art. 60, AMLD6.
 */
@RestController
@RequestMapping("/api/v1/compliance/screening")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
public class ScreeningController {

    private final ScreeningService screeningService;
    private final ScreeningRunRepository runRepository;
    private final ScreeningHitRepository hitRepository;

    public ScreeningController(ScreeningService screeningService,
                               ScreeningRunRepository runRepository,
                               ScreeningHitRepository hitRepository) {
        this.screeningService = screeningService;
        this.runRepository = runRepository;
        this.hitRepository = hitRepository;
    }

    /**
     * Global open-hit work-queue: all unresolved screening hits across all entities.
     * Enriched with run context (entityId, naturalPersonId, trigger, provider) so
     * the compliance officer can prioritise without drilling into each entity first.
     * Use {@code ?status=open} (default) to see pending hits.
     */
    @GetMapping("/hits")
    public ResponseEntity<List<OpenHitResponse>> listOpenHits(
            @RequestParam(defaultValue = "open")
            @Pattern(regexp = "open", message = "only the open work queue is supported") String status) {
        java.time.Instant now = java.time.Instant.now();
        // confirmed PEPs under an unexpired EDD approval are no longer open work
        List<ScreeningHit> hits = hitRepository.findByAcceptedIsNullOrderByCreatedAtDesc().stream()
                .filter(h -> h.blocksGate(now)).toList();
        if (hits.isEmpty()) return ResponseEntity.ok(List.of());

        Set<UUID> runIds = hits.stream().map(ScreeningHit::getRunId).collect(Collectors.toSet());
        Map<UUID, ScreeningRun> runsById = runRepository.findAllById(runIds).stream()
                .collect(Collectors.toMap(ScreeningRun::getId, r -> r));

        List<OpenHitResponse> response = hits.stream()
                .map(h -> OpenHitResponse.from(h, runsById.get(h.getRunId())))
                .toList();
        return ResponseEntity.ok(response);
    }

    /** Fetch a single screening run by ID (used by the run-detail screen). */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<ScreeningRunResponse> getRun(@PathVariable UUID runId) {
        ScreeningRun run = runRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("ScreeningRun", runId));
        return ResponseEntity.ok(ScreeningRunResponse.from(run));
    }

    /** List recent screening runs for a legal entity. */
    @GetMapping("/entities/{entityId}/runs")
    public ResponseEntity<List<ScreeningRunResponse>> listRunsByEntity(@PathVariable UUID entityId) {
        List<ScreeningRun> runs = runRepository.findByEntityIdOrderByStartedAtDesc(entityId);
        return ResponseEntity.ok(runs.stream().map(ScreeningRunResponse::from).toList());
    }

    /** Trigger on-demand screening for a legal entity (e.g. after name change). */
    @PostMapping("/entities/{entityId}/screen")
    public ResponseEntity<ScreeningRunResponse> screenEntity(
            @PathVariable UUID entityId) {
        // Always screen the canonical registry record. Accepting a caller-supplied name here
        // allowed a run attached to entity A to actually screen an unrelated, benign name.
        ScreeningRun run = screeningService.screenRegisteredEntity(entityId, ScreeningTrigger.MANUAL);
        return ResponseEntity.ok(ScreeningRunResponse.from(run));
    }

    /** Trigger on-demand screening for a natural person (beneficial owner). */
    @PostMapping("/persons/{personId}/screen")
    public ResponseEntity<ScreeningRunResponse> screenPerson(
            @PathVariable UUID personId) {
        ScreeningRun run = screeningService.screenRegisteredNaturalPerson(
                personId, ScreeningTrigger.MANUAL);
        return ResponseEntity.ok(ScreeningRunResponse.from(run));
    }

    /** List unresolved hits for a screening run. */
    @GetMapping("/runs/{runId}/hits")
    public ResponseEntity<List<ScreeningHitResponse>> listHits(@PathVariable UUID runId,
            @RequestParam(defaultValue = "open") @Pattern(regexp = "open|all") String status) {
        List<ScreeningHit> hits = "all".equals(status) ? hitRepository.findByRunId(runId)
                : hitRepository.findByRunIdAndAcceptedIsNull(runId);
        return ResponseEntity.ok(hits.stream().map(ScreeningHitResponse::from).toList());
    }

    /**
     * Confirm a PEP hit on a natural person (not a false positive): keeps the hit unresolved until an
     * EDD approval is recorded and tells kyc to set the PEP status. Step-up + second approver + note.
     */
    @PostMapping("/hits/{hitId}/confirm-pep")
    @RequiresStepUp(reason = "SCREENING_PEP_CONFIRM", requireSecondApprover = true, maxAgeMinutes = 15)
    public ResponseEntity<ScreeningHitResponse> confirmPep(
            @PathVariable UUID hitId,
            @RequestBody @Valid ConfirmPepRequest req,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        List<String> roles = jwt.getClaimAsStringList("roles");
        String actorRole = (roles != null && !roles.isEmpty()) ? roles.get(0) : "COMPLIANCE_OFFICER";
        return ResponseEntity.ok(ScreeningHitResponse.from(
                screeningService.confirmPep(hitId, actorId, actorRole, approverId, req.note())));
    }

    /**
     * Accept a false-positive screening hit.
     * Requires step-up authentication and dual control (4-eyes) because
     * accepting a sanctions hit is a regulator-grade action (GwG §10 Abs. 3).
     */
    @PostMapping("/hits/{hitId}/accept")
    @RequiresStepUp(reason = "SCREENING_HIT_ACCEPT", requireSecondApprover = true, maxAgeMinutes = 15)
    public ResponseEntity<ScreeningHitResponse> acceptHit(
            @PathVariable UUID hitId,
            @RequestBody @Valid AcceptHitRequest req,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        List<String> roles = jwt.getClaimAsStringList("roles");
        String actorRole = (roles != null && !roles.isEmpty()) ? roles.get(0) : "COMPLIANCE_OFFICER";
        // approverId comes from the request attribute StepUpEnforcementAspect populates after
        // cryptographically validating the X-Dual-Control-Token header — previously this took
        // a client-supplied req.approverActorId() from the JSON body instead, letting a caller
        // forge who "approved" a sanctions-hit dismissal (defeating the self-approval check).
        ScreeningHit hit = screeningService.acceptHit(hitId, actorId, actorRole, approverId, req.reason());
        return ResponseEntity.ok(ScreeningHitResponse.from(hit));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record ConfirmPepRequest(
            @NotBlank @Size(max = 2000) String note
    ) {}

    public record AcceptHitRequest(
            @NotBlank @Size(max = 2000) String reason
    ) {}

    /** Enriched open-hit DTO combining hit + run context for the global work-queue. */
    public record OpenHitResponse(
            UUID hitId,
            UUID runId,
            UUID entityId,
            UUID naturalPersonId,
            String listSource,
            String category,
            String matchedField,
            String matchedValue,
            Double matchScore,
            String triggerType,
            String runStatus,
            String provider,
            String createdAt,
            String startedAt,
            String resolution,
            String eddReviewDue
    ) {
        static OpenHitResponse from(ScreeningHit h, ScreeningRun r) {
            return new OpenHitResponse(
                    h.getId(), h.getRunId(),
                    r != null ? r.getEntityId() : null,
                    r != null ? r.getNaturalPersonId() : null,
                    h.getListSource(), h.getCategory().name(), h.getMatchedField(), h.getMatchedValue(),
                    h.getMatchScore() != null ? h.getMatchScore().doubleValue() : null,
                    r != null ? r.getTriggerType().name() : null,
                    r != null ? r.getStatus().name() : null,
                    r != null ? r.getProvider() : null,
                    h.getCreatedAt() != null ? h.getCreatedAt().toString() : null,
                    r != null && r.getStartedAt() != null ? r.getStartedAt().toString() : null,
                    h.getResolution() != null ? h.getResolution().name() : null,
                    h.getEddReviewDue() != null ? h.getEddReviewDue().toString() : null);
        }
    }

    public record ScreeningRunResponse(
            UUID id,
            UUID entityId,
            UUID naturalPersonId,
            String triggerType,
            String status,
            String provider,
            String startedAt,
            String completedAt,
            java.math.BigDecimal thresholdUsed,
            String dataVersion
    ) {
        static ScreeningRunResponse from(ScreeningRun r) {
            return new ScreeningRunResponse(
                    r.getId(), r.getEntityId(), r.getNaturalPersonId(),
                    r.getTriggerType().name(), r.getStatus().name(), r.getProvider(),
                    r.getStartedAt() != null ? r.getStartedAt().toString() : null,
                    r.getCompletedAt() != null ? r.getCompletedAt().toString() : null,
                    r.getThresholdUsed(), r.getDataVersion());
        }
    }

    public record ScreeningHitResponse(
            UUID id,
            UUID runId,
            String listSource,
            String category,
            String matchedField,
            String matchedValue,
            Double matchScore,
            Boolean accepted,
            String acceptReason,
            String acceptedAt,
            String resolution,
            UUID carriedFromHitId,
            String eddReviewDue
    ) {
        static ScreeningHitResponse from(ScreeningHit h) {
            return new ScreeningHitResponse(
                    h.getId(), h.getRunId(), h.getListSource(), h.getCategory().name(),
                    h.getMatchedField(), h.getMatchedValue(),
                    h.getMatchScore() != null ? h.getMatchScore().doubleValue() : null,
                    h.getAccepted(), h.getAcceptReason(),
                    h.getAcceptedAt() != null ? h.getAcceptedAt().toString() : null,
                    h.getResolution() != null ? h.getResolution().name() : null,
                    h.getCarriedFromHitId(),
                    h.getEddReviewDue() != null ? h.getEddReviewDue().toString() : null);
        }
    }
}
