package de.makibytes.registerwerk.travelrule.web;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.internal.TravelRuleService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Receives inbound Travel Rule (TFR) messages from other VASPs.
 * Endpoint is public because counterpart VASPs do not have a Registerwerk JWT. The application
 * still authenticates delivery with a deployment secret and requires the claimed VASP ID to
 * match the payload. A blank secret disables the inbox, preventing a gateway misconfiguration from
 * silently exposing compliance records to arbitrary callers.
 */
@RestController
@RequestMapping("/api/v1/public/travel-rule")
@Validated
public class TravelRuleInboxController {

    private static final Logger log = LoggerFactory.getLogger(TravelRuleInboxController.class);

    private final TravelRuleService service;
    private final byte[] configuredApiKey;

    TravelRuleInboxController(
            TravelRuleService service,
            @Value("${registerwerk.travel-rule.inbox-api-key:}") String apiKey) {
        this.service = service;
        this.configuredApiKey = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/inbox")
    public ResponseEntity<Void> receive(
            @RequestHeader("X-Vasp-Id") @NotBlank String vaspId,
            @RequestHeader(value = "X-Travel-Rule-Api-Key", required = false) String apiKey,
            @RequestBody @NotNull Ivms101.TravelRuleMessage payload) {
        if (!validApiKey(apiKey)) {
            log.warn("Rejected unauthenticated Travel Rule inbox request for VASP={}", vaspId);
            throw new AccessDeniedException("Invalid Travel Rule inbox credential");
        }
        log.info("Travel Rule inbox: message received from VASP={}", vaspId);
        service.receiveInbound(vaspId, payload);
        return ResponseEntity.accepted().build();
    }

    private boolean validApiKey(String suppliedApiKey) {
        if (configuredApiKey.length == 0 || suppliedApiKey == null) {
            return false;
        }
        return MessageDigest.isEqual(
                configuredApiKey, suppliedApiKey.getBytes(StandardCharsets.UTF_8));
    }
}
