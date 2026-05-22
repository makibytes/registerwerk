package de.makibytes.registerwerk.travelrule.web;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.internal.TravelRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Receives inbound Travel Rule (TFR) messages from other VASPs.
 * Endpoint is public (Kong-validated mTLS in production).
 * Kong validates the caller is a known VASP before forwarding here.
 */
@RestController
@RequestMapping("/api/v1/public/travel-rule")
public class TravelRuleInboxController {

    private static final Logger log = LoggerFactory.getLogger(TravelRuleInboxController.class);

    private final TravelRuleService service;

    TravelRuleInboxController(TravelRuleService service) {
        this.service = service;
    }

    @PostMapping("/inbox")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Vasp-Id", required = false) String vaspId,
            @RequestBody Ivms101.TravelRuleMessage payload) {
        log.info("Travel Rule inbox: message received from VASP={}", vaspId);
        service.receiveInbound(vaspId, payload);
        return ResponseEntity.accepted().build();
    }
}
