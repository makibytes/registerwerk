package de.makibytes.registerwerk.audit.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.makibytes.registerwerk.audit.AuditApi;
import de.makibytes.registerwerk.audit.api.AuditEventView;
import de.makibytes.registerwerk.audit.api.ChainVerificationView;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.api.PageResponse;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'AUDIT')")
public class AuditController {

    private final AuditApi auditApi;

    public AuditController(AuditApi auditApi) {
        this.auditApi = auditApi;
    }

    @GetMapping("/events")
    public ResponseEntity<PageResponse<AuditEventView>> searchEvents(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID actorId,
            Pageable pageable) {

        if (subjectType != null && subjectId != null) {
            return ResponseEntity.ok(PageResponse.of(auditApi.findBySubject(subjectType, subjectId, pageable)));
        } else if (eventType != null) {
            return ResponseEntity.ok(PageResponse.of(auditApi.findByEventType(eventType, pageable)));
        } else if (actorId != null) {
            return ResponseEntity.ok(PageResponse.of(auditApi.findByActor(actorId, pageable)));
        }
        return ResponseEntity.ok(PageResponse.of(auditApi.findAll(pageable)));
    }

    @GetMapping("/reports/kyc-overrides")
    public ResponseEntity<PageResponse<AuditEventView>> kycOverrideReport(
            @RequestParam(required = false) String jurisdiction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        return ResponseEntity.ok(PageResponse.of(auditApi.findKycOverrideApprovals(jurisdiction, from, to, pageable)));
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<AuditEventView> getEvent(@PathVariable UUID id) {
        AuditEventView event = auditApi.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("AuditEvent", id));
        return ResponseEntity.ok(event);
    }

    /** Most recently computed hash-chain integrity result (nightly job or a prior on-demand run). */
    @GetMapping("/chain/status")
    public ResponseEntity<ChainVerificationView> chainStatus() {
        return ResponseEntity.ok(auditApi.chainVerificationStatus());
    }

    /**
     * Triggers a full hash-chain verification scan on demand, rather than only unattended at
     * 03:30 nightly with results visible solely via {@code /actuator/health} — gives operators
     * a way to confirm integrity right now.
     */
    @PostMapping("/chain/verify")
    public ResponseEntity<ChainVerificationView> verifyChain() {
        return ResponseEntity.ok(auditApi.verifyChainNow());
    }
}
