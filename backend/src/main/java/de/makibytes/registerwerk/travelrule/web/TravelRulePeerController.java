package de.makibytes.registerwerk.travelrule.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.travelrule.internal.TravelRulePeerService;
import de.makibytes.registerwerk.travelrule.internal.TravelRuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operator surface of the Travel Rule inbox (6-29): peer credentials and the open-items queue. */
@RestController
@RequestMapping("/api/v1/compliance/travel-rule")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN','COMPLIANCE_OFFICER')")
@Validated
public class TravelRulePeerController {

    private final TravelRulePeerService peers;
    private final TravelRuleService service;

    TravelRulePeerController(TravelRulePeerService peers, TravelRuleService service) {
        this.peers = peers;
        this.service = service;
    }

    @GetMapping("/peers")
    public List<TravelRulePeerService.PeerView> list() {
        return peers.list();
    }

    /** Registers (or rotates the key of) a peer VASP; the HMAC key is in the response once and never again. */
    @PostMapping("/peers")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "TRAVEL_RULE_PEER_REGISTER")
    public TravelRulePeerService.CreatedPeer register(@RequestBody @Valid PeerRequest request, Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        return peers.register(request.vaspId(), request.legalName(), request.lei(),
                SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId);
    }

    @DeleteMapping("/peers/{vaspId}")
    @PreAuthorize("hasRole('REGISTRY_ADMIN')")
    @RequiresStepUp(requireSecondApprover = true, reason = "TRAVEL_RULE_PEER_DISABLE")
    public ResponseEntity<Void> disable(@PathVariable String vaspId, Authentication auth,
            @RequestAttribute(name = StepUpAttributes.DUAL_CONTROL_APPROVER_ID, required = false) UUID approverId) {
        peers.disable(vaspId, SecurityUtils.extractUserId(auth), SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"),
                approverId);
        return ResponseEntity.noContent().build();
    }

    /** Messages needing attention: FAILED/PENDING_SEND outbound, INCOMPLETE/CONFLICT/REJECTED_CASP inbound. */
    @GetMapping("/open")
    public List<Map<String, Object>> open() {
        return service.openItems();
    }

    public record PeerRequest(@NotBlank @Size(max = 255) String vaspId, @Size(max = 1000) String legalName,
                              @Size(max = 20) String lei) {}
}
