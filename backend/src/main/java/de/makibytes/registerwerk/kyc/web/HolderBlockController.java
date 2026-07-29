package de.makibytes.registerwerk.kyc.web;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.internal.SperrvermerkService;
import de.makibytes.registerwerk.kyc.web.dto.HolderBlockRequest;
import de.makibytes.registerwerk.kyc.web.dto.HolderBlockLiftRequest;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * §16 eWpG Sperrvermerk API.
 * All state-mutating operations require REGISTRY_ADMIN + step-up + 4-eyes.
 */
@RestController
@RequestMapping("/api/v1/holder-blocks")
@PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('COMPLIANCE_OFFICER')")
public class HolderBlockController {

    private final SperrvermerkService service;
    private final HolderBlockRepository repository;

    HolderBlockController(SperrvermerkService service, HolderBlockRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /** Global view: all active blocks — used by the compliance work-queue. */
    @GetMapping("/active")
    public ResponseEntity<List<HolderBlock>> listAllActive() {
        return ResponseEntity.ok(repository.findByStatusOrderByCreatedAtDesc(HolderBlock.Status.ACTIVE));
    }

    @GetMapping
    public ResponseEntity<List<HolderBlock>> listByWallet(@RequestParam String walletAddress) {
        return ResponseEntity.ok(service.findActiveByWallet(walletAddress));
    }

    @GetMapping("/entity/{entityId}")
    public ResponseEntity<List<HolderBlock>> listByEntity(@PathVariable UUID entityId) {
        return ResponseEntity.ok(service.findByEntityId(entityId));
    }

    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "SPERRVERMERK_CREATE")
    public ResponseEntity<HolderBlock> create(
            @RequestBody @Valid HolderBlockRequest req,
            @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        HolderBlock block = req.toEntity();
        UUID createdBy = UUID.fromString(jwt.getSubject());
        // Reads the aspect-validated dual-control approver — a newly created legal block must
        // record its second approver, since the endpoint enforces that one exists.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(block, createdBy, primaryRole(jwt), approverId));
    }

    @PostMapping("/{id}/lift")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "SPERRVERMERK_LIFT")
    public ResponseEntity<HolderBlock> lift(
            @PathVariable UUID id,
            @RequestBody @Valid HolderBlockLiftRequest req,
            @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        UUID liftedBy = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(service.lift(id, liftedBy, primaryRole(jwt), req.reason(), approverId));
    }

    private static String primaryRole(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return (roles != null && !roles.isEmpty()) ? roles.get(0) : "REGISTRY_ADMIN";
    }
}
