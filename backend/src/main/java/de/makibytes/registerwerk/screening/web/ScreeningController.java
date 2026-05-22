package de.makibytes.registerwerk.screening.web;

import de.makibytes.registerwerk.screening.internal.ScreeningHit;
import de.makibytes.registerwerk.screening.internal.ScreeningHitRepository;
import de.makibytes.registerwerk.screening.internal.ScreeningRun;
import de.makibytes.registerwerk.screening.internal.ScreeningRunRepository;
import de.makibytes.registerwerk.screening.internal.ScreeningService;
import de.makibytes.registerwerk.screening.internal.ScreeningTrigger;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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

    /** List recent screening runs for a legal entity. */
    @GetMapping("/entities/{entityId}/runs")
    public ResponseEntity<List<ScreeningRunResponse>> listRunsByEntity(@PathVariable UUID entityId) {
        List<ScreeningRun> runs = runRepository.findByEntityIdOrderByStartedAtDesc(entityId);
        return ResponseEntity.ok(runs.stream().map(ScreeningRunResponse::from).toList());
    }

    /** Trigger on-demand screening for a legal entity (e.g. after name change). */
    @PostMapping("/entities/{entityId}/screen")
    public ResponseEntity<ScreeningRunResponse> screenEntity(
            @PathVariable UUID entityId,
            @RequestBody @Valid ScreenEntityRequest req) {
        ScreeningRun run = screeningService.screenEntity(
                entityId, req.name(), req.countryCode(), req.lei(), ScreeningTrigger.MANUAL);
        return ResponseEntity.ok(ScreeningRunResponse.from(run));
    }

    /** Trigger on-demand screening for a natural person (beneficial owner). */
    @PostMapping("/persons/{personId}/screen")
    public ResponseEntity<ScreeningRunResponse> screenPerson(
            @PathVariable UUID personId,
            @RequestBody @Valid ScreenPersonRequest req) {
        ScreeningRun run = screeningService.screenNaturalPerson(
                personId, req.fullName(), req.countryCode(), ScreeningTrigger.MANUAL);
        return ResponseEntity.ok(ScreeningRunResponse.from(run));
    }

    /** List unresolved hits for a screening run. */
    @GetMapping("/runs/{runId}/hits")
    public ResponseEntity<List<ScreeningHitResponse>> listHits(@PathVariable UUID runId) {
        List<ScreeningHit> hits = hitRepository.findByRunIdAndAcceptedIsNull(runId);
        return ResponseEntity.ok(hits.stream().map(ScreeningHitResponse::from).toList());
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
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        ScreeningHit hit = screeningService.acceptHit(hitId, actorId, req.approverActorId(), req.reason());
        return ResponseEntity.ok(ScreeningHitResponse.from(hit));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record ScreenEntityRequest(
            String name,
            String countryCode,
            String lei
    ) {}

    public record ScreenPersonRequest(
            @NotBlank String fullName,
            String countryCode
    ) {}

    public record AcceptHitRequest(
            @NotBlank String reason,
            UUID approverActorId
    ) {}

    public record ScreeningRunResponse(
            UUID id,
            UUID entityId,
            UUID naturalPersonId,
            String triggerType,
            String status,
            String provider,
            String startedAt,
            String completedAt
    ) {
        static ScreeningRunResponse from(ScreeningRun r) {
            return new ScreeningRunResponse(
                    r.getId(), r.getEntityId(), r.getNaturalPersonId(),
                    r.getTriggerType().name(), r.getStatus().name(), r.getProvider(),
                    r.getStartedAt() != null ? r.getStartedAt().toString() : null,
                    r.getCompletedAt() != null ? r.getCompletedAt().toString() : null);
        }
    }

    public record ScreeningHitResponse(
            UUID id,
            UUID runId,
            String listSource,
            String matchedField,
            String matchedValue,
            Double matchScore,
            Boolean accepted,
            String acceptReason,
            String acceptedAt
    ) {
        static ScreeningHitResponse from(ScreeningHit h) {
            return new ScreeningHitResponse(
                    h.getId(), h.getRunId(), h.getListSource(),
                    h.getMatchedField(), h.getMatchedValue(),
                    h.getMatchScore() != null ? h.getMatchScore().doubleValue() : null,
                    h.getAccepted(), h.getAcceptReason(),
                    h.getAcceptedAt() != null ? h.getAcceptedAt().toString() : null);
        }
    }
}
