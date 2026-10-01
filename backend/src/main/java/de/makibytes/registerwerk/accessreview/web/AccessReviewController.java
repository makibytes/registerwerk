package de.makibytes.registerwerk.accessreview.web;

import de.makibytes.registerwerk.accessreview.api.AccessReviewDecision;
import de.makibytes.registerwerk.accessreview.internal.AccessReviewService;
import de.makibytes.registerwerk.accessreview.web.dto.CampaignResponse;
import de.makibytes.registerwerk.accessreview.web.dto.ItemResponse;
import de.makibytes.registerwerk.accessreview.web.dto.RecordDecisionRequest;
import de.makibytes.registerwerk.accessreview.web.dto.ReopenItemRequest;
import de.makibytes.registerwerk.accessreview.web.dto.StartCampaignRequest;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Access recertification (entitlement review) campaigns — Base path
 * {@code /api/v1/access-reviews}.
 *
 * <p>Starting/closing a campaign is a REGISTRY_ADMIN action (campaign administration); recording
 * a decision on an item is open to REGISTRY_ADMIN or COMPLIANCE_OFFICER, since a compliance
 * officer reviewing entitlements a REGISTRY_ADMIN started is exactly the maker/checker split this
 * codebase already enforces elsewhere for dual control.
 */
@RestController
@RequestMapping("/api/v1/access-reviews")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER')")
public class AccessReviewController {

    private final AccessReviewService service;

    public AccessReviewController(AccessReviewService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<CampaignResponse> startCampaign(
            @RequestBody @Valid StartCampaignRequest request, Authentication auth) {
        var campaign = service.startCampaign(request.name(), request.dueDate(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.status(HttpStatus.CREATED).body(CampaignResponse.from(campaign));
    }

    @GetMapping
    public ResponseEntity<List<CampaignResponse>> listCampaigns() {
        return ResponseEntity.ok(service.listCampaigns().stream().map(CampaignResponse::from).toList());
    }

    @GetMapping("/{campaignId}")
    public ResponseEntity<CampaignResponse> getCampaign(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(CampaignResponse.from(service.getCampaign(campaignId)));
    }

    @GetMapping("/{campaignId}/items")
    public ResponseEntity<List<ItemResponse>> listItems(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(service.listItems(campaignId).stream().map(ItemResponse::from).toList());
    }

    /**
     * Needs a step-up token (send it as the bearer). Decisions are write-once; REVOKED on a
     * privileged account is a proposal until a second reviewer confirms with REVOKED.
     */
    @PostMapping("/{campaignId}/items/{itemId}/decision")
    @RequiresStepUp(reason = "ACCESS_REVIEW_DECISION")
    public ResponseEntity<ItemResponse> recordDecision(
            @PathVariable UUID campaignId, @PathVariable UUID itemId,
            @RequestBody @Valid RecordDecisionRequest request, Authentication auth) {
        var item = service.recordDecision(campaignId, itemId, AccessReviewDecision.valueOf(request.decision()),
                request.notes(), SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(ItemResponse.from(item));
    }

    /** The only way to correct a decision: re-snapshots the live account and returns the item to PENDING. */
    @PostMapping("/{campaignId}/items/{itemId}/reopen")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(reason = "ACCESS_REVIEW_REOPEN")
    public ResponseEntity<ItemResponse> reopen(
            @PathVariable UUID campaignId, @PathVariable UUID itemId,
            @RequestBody @Valid ReopenItemRequest request, Authentication auth) {
        var item = service.reopen(campaignId, itemId, request.reason(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(ItemResponse.from(item));
    }

    @PostMapping("/{campaignId}/close")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    public ResponseEntity<CampaignResponse> closeCampaign(@PathVariable UUID campaignId, Authentication auth) {
        var campaign = service.closeCampaign(campaignId,
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"));
        return ResponseEntity.ok(CampaignResponse.from(campaign));
    }
}
