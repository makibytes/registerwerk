package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.customer.internal.DsarErasureService;
import de.makibytes.registerwerk.customer.web.dto.ErasureRequestDtos.ErasureRequestResponse;
import de.makibytes.registerwerk.customer.web.dto.ErasureRequestDtos.ResolveErasureRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Operator-facing queue for DSGVO Art. 17 erasure requests. This is the work list that
 * closes the loop the customer endpoint ({@code /me/dsar/erasure}) opens: staff review
 * each open request against statutory retention and mark it completed or rejected.
 */
@RestController
@RequestMapping("/api/v1/admin/dsar/erasure-requests")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class AdminErasureRequestController {

    private final DsarErasureService erasureService;

    AdminErasureRequestController(DsarErasureService erasureService) {
        this.erasureService = erasureService;
    }

    /** Lists the open (REQUESTED / IN_REVIEW) erasure requests, oldest first. */
    @GetMapping
    public ResponseEntity<List<ErasureRequestResponse>> listOpen() {
        List<ErasureRequestResponse> body = erasureService.listOpen().stream()
                .map(ErasureRequestResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<ErasureRequestResponse> complete(
            @PathVariable UUID id, @RequestBody(required = false) ResolveErasureRequest req, Authentication auth) {
        UUID operatorId = SecurityUtils.extractUserId(auth);
        String note = req != null ? req.note() : null;
        return ResponseEntity.ok(ErasureRequestResponse.from(erasureService.complete(id, operatorId, note)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ErasureRequestResponse> reject(
            @PathVariable UUID id, @RequestBody(required = false) ResolveErasureRequest req, Authentication auth) {
        UUID operatorId = SecurityUtils.extractUserId(auth);
        String note = req != null ? req.note() : null;
        return ResponseEntity.ok(ErasureRequestResponse.from(erasureService.reject(id, operatorId, note)));
    }
}
