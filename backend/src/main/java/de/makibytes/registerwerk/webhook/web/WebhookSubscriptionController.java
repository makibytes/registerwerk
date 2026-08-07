package de.makibytes.registerwerk.webhook.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.webhook.api.WebhookSubscription;
import de.makibytes.registerwerk.webhook.internal.WebhookSubscriptionService;
import de.makibytes.registerwerk.webhook.web.dto.CreateWebhookSubscriptionRequest;
import de.makibytes.registerwerk.webhook.web.dto.SetEnabledRequest;
import de.makibytes.registerwerk.webhook.web.dto.WebhookDeliveryResponse;
import de.makibytes.registerwerk.webhook.web.dto.WebhookSubscriptionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-service webhook subscription management for a legal entity's own curated event stream
 * (F-BLOCKER-2) — previously the only way to learn something happened was to poll REST.
 */
@RestController
@RequestMapping("/api/v1/me/webhooks")
@PreAuthorize("isAuthenticated()")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService service;

    public WebhookSubscriptionController(WebhookSubscriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WebhookSubscriptionResponse> create(
            @RequestBody @Valid CreateWebhookSubscriptionRequest request, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        WebhookSubscription created = service.create(
                entityId, request.url(), request.eventTypes(), SecurityUtils.extractUserId(auth));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WebhookSubscriptionResponse.from(created, created.getSecret()));
    }

    @GetMapping
    public ResponseEntity<List<WebhookSubscriptionResponse>> list(Authentication auth) {
        UUID entityId = requireEntityId(auth);
        return ResponseEntity.ok(service.listForEntity(entityId).stream()
                .map(WebhookSubscriptionResponse::from).toList());
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<Void> setEnabled(@PathVariable UUID id, @RequestBody SetEnabledRequest request, Authentication auth) {
        service.setEnabled(requireEntityId(auth), id, request.enabled());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth) {
        service.delete(requireEntityId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/deliveries")
    public ResponseEntity<List<WebhookDeliveryResponse>> deliveries(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(service.listDeliveries(requireEntityId(auth), id).stream()
                .map(WebhookDeliveryResponse::from).toList());
    }

    private UUID requireEntityId(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            throw new IllegalArgumentException("Authenticated entity context is required");
        }
        return entityId;
    }
}
