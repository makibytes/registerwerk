package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.endpoint.EndpointService;
import de.makibytes.registerwerk.domain.endpoint.AddressEndpoint;
import de.makibytes.registerwerk.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the address-endpoint addressbook.
 *
 * <p>Operators manage their own entries (ownerType=OPERATOR).
 * Customer entity users manage entries scoped to their legal entity (ownerType=ENTITY).
 *
 * <p>Resolution is context-aware:
 * <ul>
 *   <li>REGISTRY_ADMIN — all KYC'd wallet addresses + operator endpoints
 *   <li>Others — entity-scoped endpoints + wallet addresses of related entities
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/endpoints")
@PreAuthorize("isAuthenticated()")
public class EndpointController {

    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @GetMapping
    public ResponseEntity<List<EndpointResponse>> listEndpoints(Authentication auth) {
        boolean isOperator = hasRole(auth, "REGISTRY_ADMIN");
        UUID entityId = extractEntityId(auth);
        List<AddressEndpoint> endpoints = endpointService.listEndpoints(isOperator, entityId);
        return ResponseEntity.ok(endpoints.stream().map(EndpointResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<EndpointResponse> createEndpoint(
            @RequestBody @Valid EndpointCreateRequest request,
            Authentication auth) {
        boolean isOperator = hasRole(auth, "REGISTRY_ADMIN");
        UUID entityId = extractEntityId(auth);
        AddressEndpoint ep = endpointService.createEndpoint(
                isOperator, entityId,
                request.address(), request.addressType(), request.name(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(EndpointResponse.from(ep));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<EndpointResponse> updateEndpoint(
            @PathVariable UUID id,
            @RequestBody @Valid EndpointUpdateRequest request,
            Authentication auth) {
        boolean isOperator = hasRole(auth, "REGISTRY_ADMIN");
        UUID entityId = extractEntityId(auth);
        AddressEndpoint ep = endpointService.updateEndpoint(id, isOperator, entityId,
                request.name(), request.notes());
        return ResponseEntity.ok(EndpointResponse.from(ep));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEndpoint(@PathVariable UUID id, Authentication auth) {
        boolean isOperator = hasRole(auth, "REGISTRY_ADMIN");
        UUID entityId = extractEntityId(auth);
        endpointService.deleteEndpoint(id, isOperator, entityId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bulk-resolves a list of addresses to names within the caller's scope.
     * Returns only addresses that could be resolved; unresolvable ones are omitted.
     */
    @PostMapping("/resolve")
    public ResponseEntity<AddressResolveResponse> resolveAddresses(
            @RequestBody @Valid AddressResolveRequest request,
            Authentication auth) {
        boolean isOperator = hasRole(auth, "REGISTRY_ADMIN");
        UUID entityId = extractEntityId(auth);
        return ResponseEntity.ok(new AddressResolveResponse(
                endpointService.resolveAddresses(request.addresses(), isOperator, entityId)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasRole(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    private UUID extractEntityId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String id = jwt.getClaimAsString("entity_id");
            if (id != null) {
                try {
                    return UUID.fromString(id);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }
}
