package de.makibytes.registerwerk.indexer.web;

import de.makibytes.registerwerk.indexer.internal.ChainDriftEvent;
import de.makibytes.registerwerk.indexer.internal.ChainDriftService;
import de.makibytes.registerwerk.indexer.internal.ChainDriftStatus;
import de.makibytes.registerwerk.indexer.web.dto.ChainDriftEventResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Case management for {@link ChainDriftEvent} — registry-vs-chain balance divergences detected
 * by {@code ChainDriftDetectionJob} (eWpG §16 / KryptoFAV §6 canonical-registry control).
 * Previously write-only: the job persisted OPEN rows and nothing else in the codebase ever read
 * or closed one, so an ops team had no way to see, triage, or resolve a divergence without
 * direct database access — this is the registry's most important control and it terminated in
 * a log line.
 */
@RestController
@RequestMapping("/api/v1/chain-drift")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER','AUDIT')")
public class ChainDriftController {

    private final ChainDriftService service;

    public ChainDriftController(ChainDriftService service) {
        this.service = service;
    }

    /** Defaults to OPEN — the work queue. Pass {@code ?status=RESOLVED} for the closed history. */
    @GetMapping
    public ResponseEntity<PageResponse<ChainDriftEventResponse>> list(
            @RequestParam(required = false) ChainDriftStatus status,
            @RequestParam(required = false) UUID assetId,
            Pageable pageable) {
        Page<ChainDriftEvent> page = service.list(status != null ? status : ChainDriftStatus.OPEN, assetId, pageable);
        return ResponseEntity.ok(PageResponse.of(page.map(ChainDriftEventResponse::from)));
    }

    @GetMapping("/open-count")
    public ResponseEntity<Long> openCount() {
        return ResponseEntity.ok(service.countOpen());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChainDriftEventResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ChainDriftEventResponse.from(service.get(id)));
    }

    /**
     * Closes a case with a mandatory explanation. Not itself a fund-moving action — the actual
     * registry or on-chain correction (if any) happens through the existing correction
     * endpoints ({@code CorrectionCapabilityService}) — so this is a normal REGISTRY_ADMIN /
     * COMPLIANCE_OFFICER action, not step-up/dual-control gated.
     */
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ChainDriftEventResponse> resolve(
            @PathVariable UUID id,
            @RequestBody @Valid ResolveRequest request,
            Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        String actorRole = SecurityUtils.extractRoles(auth).stream().findFirst().orElse("REGISTRY_ADMIN");
        ChainDriftEvent resolved = service.resolve(id, actorId, actorRole, request.notes());
        return ResponseEntity.ok(ChainDriftEventResponse.from(resolved));
    }

    public record ResolveRequest(@NotBlank String notes) {}
}
