package de.makibytes.registerwerk.travelrule.web;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.internal.TravelRuleProperties;
import de.makibytes.registerwerk.travelrule.internal.TravelRulePeerService;
import de.makibytes.registerwerk.travelrule.internal.TravelRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Receives inbound Travel Rule (TFR) messages from other VASPs. The endpoint is public (peers hold no
 * Registerwerk JWT) but every request must be authenticated as a registered peer (6-29):
 * {@code X-Vasp-Id}, {@code X-Registerwerk-Timestamp} (epoch seconds) and
 * {@code X-Registerwerk-Peer-Signature} = hex HMAC-SHA256(peerKey, timestamp|vaspId|sha256(body)). The
 * identity used downstream is the authenticated peer, never a free header. The pre-V42 shared key is only
 * honoured when {@code registerwerk.travel-rule.legacy-shared-key=true} outside production mode.
 */
@RestController
@RequestMapping("/api/v1/public/travel-rule")
public class TravelRuleInboxController {

    private static final Logger log = LoggerFactory.getLogger(TravelRuleInboxController.class);

    private final TravelRuleService service;
    private final TravelRulePeerService peers;
    private final TravelRuleProperties properties;
    private final ObjectMapper objectMapper;
    private final byte[] legacyKey;
    private final boolean productionMode;

    TravelRuleInboxController(
            TravelRuleService service, TravelRulePeerService peers, TravelRuleProperties properties,
            ObjectMapper objectMapper,
            @Value("${registerwerk.travel-rule.inbox-api-key:}") String apiKey,
            @Value("${REGISTERWERK_PRODUCTION_MODE:false}") boolean productionMode) {
        this.service = service;
        this.peers = peers;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.legacyKey = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
        this.productionMode = productionMode;
    }

    @PostMapping(value = "/inbox", consumes = "application/json")
    public ResponseEntity<Void> receive(
            @RequestHeader("X-Vasp-Id") String vaspId,
            @RequestHeader(value = "X-Registerwerk-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Registerwerk-Peer-Signature", required = false) String signature,
            @RequestHeader(value = "X-Travel-Rule-Api-Key", required = false) String legacyApiKey,
            @RequestBody byte[] body) {
        if (body.length > properties.getInboundMaxBodyBytes()) {
            return ResponseEntity.status(413).build();
        }
        String peerId;
        if (signature != null) {
            try {
                peerId = peers.authenticate(vaspId, timestamp, signature, body);
            } catch (AccessDeniedException e) {
                log.warn("Rejected Travel Rule inbox request: peer authentication failed for X-Vasp-Id={}", vaspId);
                throw e;
            }
        } else if (legacyAccepted(legacyApiKey)) {
            log.warn("Travel Rule inbox: legacy shared key used (X-Vasp-Id={} is NOT authenticated)", vaspId);
            peerId = vaspId;
        } else {
            log.warn("Rejected unauthenticated Travel Rule inbox request for X-Vasp-Id={}", vaspId);
            throw new AccessDeniedException("Invalid Travel Rule peer credential");
        }
        Ivms101.TravelRuleMessage payload;
        try {
            payload = objectMapper.readValue(body, Ivms101.TravelRuleMessage.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed IVMS-101 payload");
        }
        service.receiveInbound(peerId, payload);
        return ResponseEntity.accepted().build();
    }

    private boolean legacyAccepted(String supplied) {
        return properties.isLegacySharedKey() && !productionMode && legacyKey.length > 0 && supplied != null
                && MessageDigest.isEqual(legacyKey, supplied.getBytes(StandardCharsets.UTF_8));
    }
}
