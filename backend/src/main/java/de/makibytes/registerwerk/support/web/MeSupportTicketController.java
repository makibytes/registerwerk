package de.makibytes.registerwerk.support.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.support.api.SupportTicket;
import de.makibytes.registerwerk.support.internal.SupportTicketService;
import de.makibytes.registerwerk.support.web.dto.AddMessageRequest;
import de.makibytes.registerwerk.support.web.dto.CreateSupportTicketRequest;
import de.makibytes.registerwerk.support.web.dto.SupportTicketMessageResponse;
import de.makibytes.registerwerk.support.web.dto.SupportTicketResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Customer self-service support tickets — previously no support/ticketing concept existed at
 * all; DSAR erasure and KYC document review were the only customer-initiated channels, both
 * narrow compliance flows, not general support.
 * Base path: /api/v1/me/support-tickets
 */
@RestController
@RequestMapping("/api/v1/me/support-tickets")
@PreAuthorize("isAuthenticated()")
public class MeSupportTicketController {

    private final SupportTicketService service;

    public MeSupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<SupportTicketResponse>> list(Authentication auth) {
        UUID entityId = requireEntityId(auth);
        return ResponseEntity.ok(service.listForEntity(entityId).stream().map(SupportTicketResponse::of).toList());
    }

    @PostMapping
    public ResponseEntity<SupportTicketResponse> create(@Valid @RequestBody CreateSupportTicketRequest request,
                                                          Authentication auth) {
        UUID entityId = requireEntityId(auth);
        UUID userId = SecurityUtils.extractUserId(auth);
        SupportTicket ticket = service.create(entityId, userId, request.subject(), request.description(),
                request.category(), request.priority());
        return ResponseEntity.status(HttpStatus.CREATED).body(SupportTicketResponse.of(ticket));
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<SupportTicketResponse> get(@PathVariable UUID ticketId, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        return ResponseEntity.ok(SupportTicketResponse.of(service.requireOwnedTicket(ticketId, entityId)));
    }

    @GetMapping("/{ticketId}/messages")
    public ResponseEntity<List<SupportTicketMessageResponse>> messages(@PathVariable UUID ticketId, Authentication auth) {
        UUID entityId = requireEntityId(auth);
        service.requireOwnedTicket(ticketId, entityId);
        return ResponseEntity.ok(service.listMessages(ticketId).stream().map(SupportTicketMessageResponse::of).toList());
    }

    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<SupportTicketMessageResponse> addMessage(@PathVariable UUID ticketId,
                                                                     @Valid @RequestBody AddMessageRequest request,
                                                                     Authentication auth) {
        UUID entityId = requireEntityId(auth);
        service.requireOwnedTicket(ticketId, entityId);
        UUID userId = SecurityUtils.extractUserId(auth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SupportTicketMessageResponse.of(service.addMessage(ticketId, userId, false, request.body())));
    }

    private UUID requireEntityId(Authentication auth) {
        UUID entityId = SecurityUtils.extractEntityId(auth);
        if (entityId == null) {
            throw new IllegalArgumentException("Authenticated entity context is required");
        }
        return entityId;
    }
}
