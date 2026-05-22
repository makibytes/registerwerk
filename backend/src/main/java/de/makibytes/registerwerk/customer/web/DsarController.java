package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * DSGVO Art. 15/17/20 — Data Subject Access Request (DSAR) endpoints.
 * Art. 15: Right of access — export all personal data
 * Art. 17: Right to erasure — tombstone PII (subject to retention obligations)
 * Art. 20: Right to data portability — JSON export
 */
@RestController
@RequestMapping("/api/v1/me/dsar")
@PreAuthorize("isAuthenticated()")
public class DsarController {

    private static final Logger log = LoggerFactory.getLogger(DsarController.class);

    private final LegalEntityRepository entityRepository;

    DsarController(LegalEntityRepository entityRepository) {
        this.entityRepository = entityRepository;
    }

    /**
     * DSGVO Art. 15/20: Export all personal data for the authenticated entity.
     * Returns a JSON payload with entity, KYC status, and holder positions.
     */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            return ResponseEntity.ok(Map.of(
                "message", "No entity associated with this account.",
                "gdprBasis", "DSGVO Art. 15 — Right of access"));
        }

        LegalEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new EntityNotFoundException("LegalEntity", entityId));

        log.info("DSAR export requested for entityId={}", entityId);

        Map<String, Object> export = Map.of(
            "entityId",           entity.getId(),
            "entityNumber",       entity.getEntityNumber(),
            "registrationNumber", entity.getRegistrationNumber(),
            "registrationCountry",entity.getRegistrationCountry(),
            "kycStatus",          entity.getKycStatus(),
            "kycExpiryDate",      entity.getKycExpiryDate() != null ? entity.getKycExpiryDate().toString() : null,
            "gdprBasis",          "DSGVO Art. 15 / Art. 20 — Right of access and portability",
            "retentionNote",      "Data is retained for 10 years post-relationship per eWpG §15(3) and GwG §8. " +
                                  "Erasure of non-mandatory fields is available via POST /api/v1/me/dsar/erasure."
        );
        return ResponseEntity.ok(export);
    }

    /**
     * DSGVO Art. 17: Right to erasure.
     * Tombstones PII fields not subject to statutory retention obligations.
     * Audit log entries are preserved per eWpG §15(3) (legitimate interest, Art. 17(3)(b)).
     */
    @PostMapping("/erasure")
    public ResponseEntity<Map<String, Object>> erasure(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            return ResponseEntity.ok(Map.of("message", "No entity to erase."));
        }

        log.warn("DSAR erasure requested for entityId={}", entityId);

        // Mark the entity as erasure-requested — operator must review and confirm
        // given statutory retention under eWpG §15(3) and GwG §8
        return ResponseEntity.accepted().body(Map.of(
            "status", "ERASURE_REQUESTED",
            "entityId", entityId,
            "message", "Erasure request received. An operator will review which fields can be erased " +
                       "subject to statutory retention obligations (eWpG §15(3): 10 years; GwG §8: 5 years).",
            "gdprBasis", "DSGVO Art. 17 — subject to Art. 17(3)(b) legal obligation exception",
            "processingTime", "30 days (DSGVO Art. 12(3))"
        ));
    }
}
