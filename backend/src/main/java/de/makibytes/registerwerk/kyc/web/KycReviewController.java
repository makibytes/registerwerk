package de.makibytes.registerwerk.kyc.web;

import de.makibytes.registerwerk.kyc.internal.KycReviewService;
import de.makibytes.registerwerk.kyc.web.dto.KycQueueItemResponse;
import de.makibytes.registerwerk.kyc.web.dto.KycReviewResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the compliance officer's KYC work (T8-03): the queue of entities that need a decision and a
 * scoped review of one entity. The decisions themselves stay on the existing endpoints (KYC approve/reject,
 * jurisdiction approvals, beneficial-owner verify/cease, all with step-up and a second approver); EDD approval
 * remains REGISTRY_ADMIN. The full entity read and everything else on the entity API is unchanged.
 */
@RestController
@RequestMapping("/api/v1/kyc")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
public class KycReviewController {

    private final KycReviewService reviewService;

    public KycReviewController(KycReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** Entities awaiting a KYC / EDD / beneficial-owner decision, expiring KYC and evidence gaps. */
    @GetMapping("/queue")
    public ResponseEntity<List<KycQueueItemResponse>> queue() {
        return ResponseEntity.ok(reviewService.queue().stream().map(KycQueueItemResponse::from).toList());
    }

    /** What the decision on this entity rests on: identity, documents, checklist, owners, screening, history. */
    @GetMapping("/entities/{entityId}/review")
    public ResponseEntity<KycReviewResponse> review(@PathVariable UUID entityId) {
        return ResponseEntity.ok(KycReviewResponse.from(reviewService.review(entityId)));
    }
}
