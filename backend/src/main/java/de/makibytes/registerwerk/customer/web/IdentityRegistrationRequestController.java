package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.audit.api.AuditableEvent;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Customer self-service: request that the registry operator registers the
 * customer's wallet in the on-chain Identity Registry (ONCHAINID/ERC-3643).
 *
 * The operator reviews the request in the operator portal and can then deploy
 * the ONCHAINID contract for the customer's wallet address.
 */
@RestController
@RequestMapping("/api/v1/me/identity-registration-requests")
@PreAuthorize("isAuthenticated()")
public class IdentityRegistrationRequestController {

    private final ApplicationEventPublisher events;

    IdentityRegistrationRequestController(ApplicationEventPublisher events) {
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> request(
            @RequestBody @Valid IdentityRegistrationRequest req,
            Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);

        UUID assetUuid = UUID.fromString(req.assetId());
        UUID deploymentUuid = UUID.fromString(req.deploymentId());

        events.publishEvent(new IdentityRegistrationRequestedEvent(
                entityId, assetUuid, deploymentUuid, req.walletAddress(), req.note()));

        return ResponseEntity.accepted().body(Map.of(
                "status",    "REQUESTED",
                "entityId",  entityId != null ? entityId.toString() : null,
                "assetId",   req.assetId().toString(),
                "message",   "Your identity registration request has been submitted. " +
                             "The registry operator will review and register your wallet."
        ));
    }

    public record IdentityRegistrationRequest(
            @NotBlank(message = "assetId is required") String assetId,
            @NotBlank(message = "deploymentId is required") String deploymentId,
            @NotBlank(message = "walletAddress is required") String walletAddress,
            String note
    ) {}

    record IdentityRegistrationRequestedEvent(
            UUID entityId,
            UUID assetId,
            UUID deploymentId,
            String walletAddress,
            String note
    ) implements AuditableEvent {
        @Override public String eventType()    { return "IDENTITY_REGISTRATION_REQUESTED"; }
        @Override public String subjectType()  { return "ASSET"; }
        @Override public UUID   subjectId()    { return assetId; }
        @Override public UUID   actorId()      { return entityId; }
        @Override public String actorRole()    { return "CUSTOMER"; }
        @Override public Map<String, Object> payload() {
            return Map.of(
                    "entityId",      entityId != null ? entityId.toString() : "unknown",
                    "deploymentId",  deploymentId.toString(),
                    "walletAddress", walletAddress,
                    "note",          note != null ? note : ""
            );
        }
    }
}
