package de.makibytes.registerwerk.kyc.web;

import de.makibytes.registerwerk.kyc.internal.KycEvidenceService;
import de.makibytes.registerwerk.kyc.web.dto.EvidenceGapResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only KYC evidence-gap report for operators (6-15). */
@RestController
@RequestMapping("/api/v1/kyc")
public class KycEvidenceController {

    private final KycEvidenceService evidenceService;

    public KycEvidenceController(KycEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    /**
     * APPROVED entities that would fail today's approval checks (checklist, beneficial-owner coverage, expiry
     * cap, PEP/EDD, screening). Existing approvals are not downgraded; the list is for the next review.
     */
    @GetMapping("/evidence-gaps")
    @PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT')")
    public ResponseEntity<List<EvidenceGapResponse>> evidenceGaps() {
        return ResponseEntity.ok(evidenceService.evidenceGaps().stream().map(EvidenceGapResponse::from).toList());
    }
}
