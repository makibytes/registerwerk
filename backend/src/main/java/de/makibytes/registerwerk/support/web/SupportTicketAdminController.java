package de.makibytes.registerwerk.support.web;

import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.shared.api.PageResponse;
import de.makibytes.registerwerk.support.api.SupportTicket;
import de.makibytes.registerwerk.support.internal.SupportTicketService;
import de.makibytes.registerwerk.support.web.dto.AddMessageRequest;
import de.makibytes.registerwerk.support.web.dto.ResolveTicketRequest;
import de.makibytes.registerwerk.support.web.dto.SupportTicketMessageResponse;
import de.makibytes.registerwerk.support.web.dto.SupportTicketResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Operator support-ticket queue.
 * Base path: /api/v1/admin/support-tickets
 */
@RestController
@RequestMapping("/api/v1/admin/support-tickets")
@PreAuthorize("hasAnyRole('REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT')")
public class SupportTicketAdminController {

    private final SupportTicketService service;

    public SupportTicketAdminController(SupportTicketService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PageResponse<SupportTicketResponse>> list(
            @RequestParam(required = false) SupportTicket.Status status, Pageable pageable) {
        return ResponseEntity.ok(PageResponse.of(service.listAll(status, pageable).map(SupportTicketResponse::of)));
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<SupportTicketResponse> get(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(SupportTicketResponse.of(service.requireTicket(ticketId)));
    }

    @GetMapping("/{ticketId}/messages")
    public ResponseEntity<List<SupportTicketMessageResponse>> messages(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(service.listMessages(ticketId).stream().map(SupportTicketMessageResponse::of).toList());
    }

    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<SupportTicketMessageResponse> addMessage(@PathVariable UUID ticketId,
                                                                     @Valid @RequestBody AddMessageRequest request,
                                                                     Authentication auth) {
        UUID userId = SecurityUtils.extractUserId(auth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SupportTicketMessageResponse.of(service.addMessage(ticketId, userId, true, request.body())));
    }

    @PostMapping("/{ticketId}/assign")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('COMPLIANCE_OFFICER')")
    public ResponseEntity<SupportTicketResponse> assign(@PathVariable UUID ticketId,
                                                          @RequestParam UUID assigneeId, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        return ResponseEntity.ok(SupportTicketResponse.of(service.assign(ticketId, assigneeId, actorId)));
    }

    @PostMapping("/{ticketId}/resolve")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('COMPLIANCE_OFFICER')")
    public ResponseEntity<SupportTicketResponse> resolve(@PathVariable UUID ticketId,
                                                           @Valid @RequestBody ResolveTicketRequest request,
                                                           Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        return ResponseEntity.ok(SupportTicketResponse.of(service.resolve(ticketId, actorId, request.resolutionNotes())));
    }

    @PostMapping("/{ticketId}/close")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('COMPLIANCE_OFFICER')")
    public ResponseEntity<SupportTicketResponse> close(@PathVariable UUID ticketId, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        return ResponseEntity.ok(SupportTicketResponse.of(service.close(ticketId, actorId)));
    }

    @PostMapping("/{ticketId}/reopen")
    @PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('COMPLIANCE_OFFICER')")
    public ResponseEntity<SupportTicketResponse> reopen(@PathVariable UUID ticketId, Authentication auth) {
        UUID actorId = SecurityUtils.extractUserId(auth);
        return ResponseEntity.ok(SupportTicketResponse.of(service.reopen(ticketId, actorId)));
    }
}
