package de.makibytes.registerwerk.support.internal;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.support.api.SupportTicket;
import de.makibytes.registerwerk.support.api.SupportTicketMessage;
import de.makibytes.registerwerk.support.api.SupportTicketMessageRepository;
import de.makibytes.registerwerk.support.api.SupportTicketRepository;
import de.makibytes.registerwerk.support.events.SupportTicketCreatedEvent;
import de.makibytes.registerwerk.support.events.SupportTicketStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);

    private final SupportTicketRepository tickets;
    private final SupportTicketMessageRepository messages;
    private final ApplicationEventPublisher events;

    public SupportTicketService(SupportTicketRepository tickets, SupportTicketMessageRepository messages,
                                 ApplicationEventPublisher events) {
        this.tickets = tickets;
        this.messages = messages;
        this.events = events;
    }

    public SupportTicket create(UUID entityId, UUID createdBy, String subject, String description,
                                SupportTicket.Category category, SupportTicket.Priority priority) {
        SupportTicket ticket = new SupportTicket();
        ticket.setEntityId(entityId);
        ticket.setCreatedBy(createdBy);
        ticket.setSubject(subject);
        ticket.setDescription(description);
        ticket.setCategory(category);
        ticket.setPriority(priority != null ? priority : SupportTicket.Priority.NORMAL);
        SupportTicket saved = tickets.save(ticket);
        events.publishEvent(new SupportTicketCreatedEvent(saved.getId(), entityId, createdBy, null,
                saved.getCategory().name(), saved.getPriority().name()));
        log.info("Support ticket created: id={} entity={} category={}", saved.getId(), entityId, category);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> listForEntity(UUID entityId) {
        return tickets.findByEntityIdOrderByCreatedAtDesc(entityId);
    }

    @Transactional(readOnly = true)
    public Page<SupportTicket> listAll(SupportTicket.Status status, Pageable pageable) {
        return status != null ? tickets.findByStatusOrderByCreatedAtDesc(status, pageable)
                               : tickets.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public SupportTicket requireOwnedTicket(UUID ticketId, UUID entityId) {
        SupportTicket ticket = requireTicket(ticketId);
        if (!ticket.getEntityId().equals(entityId)) {
            throw new EntityNotFoundException("SupportTicket", ticketId);
        }
        return ticket;
    }

    @Transactional(readOnly = true)
    public SupportTicket requireTicket(UUID ticketId) {
        return tickets.findById(ticketId).orElseThrow(() -> new EntityNotFoundException("SupportTicket", ticketId));
    }

    @Transactional(readOnly = true)
    public List<SupportTicketMessage> listMessages(UUID ticketId) {
        return messages.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    public SupportTicketMessage addMessage(UUID ticketId, UUID authorId, boolean authorIsOperator, String body) {
        SupportTicketMessage message = new SupportTicketMessage();
        message.setTicketId(ticketId);
        message.setAuthorId(authorId);
        message.setAuthorIsOperator(authorIsOperator);
        message.setBody(body);
        SupportTicketMessage saved = messages.save(message);

        // A customer reply on an operator-answered ticket should reopen it if it had been
        // marked RESOLVED — otherwise it silently sits RESOLVED while the customer is still
        // waiting for a response to their follow-up.
        SupportTicket ticket = requireTicket(ticketId);
        if (!authorIsOperator && ticket.getStatus() == SupportTicket.Status.RESOLVED) {
            transitionStatus(ticket, SupportTicket.Status.IN_PROGRESS, authorId);
        }
        return saved;
    }

    public SupportTicket assign(UUID ticketId, UUID assigneeId, UUID actorId) {
        SupportTicket ticket = requireTicket(ticketId);
        ticket.setAssignedTo(assigneeId);
        if (ticket.getStatus() == SupportTicket.Status.OPEN) {
            transitionStatus(ticket, SupportTicket.Status.IN_PROGRESS, actorId);
        }
        return tickets.save(ticket);
    }

    public SupportTicket resolve(UUID ticketId, UUID actorId, String resolutionNotes) {
        SupportTicket ticket = requireTicket(ticketId);
        ticket.setResolutionNotes(resolutionNotes);
        ticket.setResolvedAt(Instant.now());
        return transitionStatus(ticket, SupportTicket.Status.RESOLVED, actorId);
    }

    public SupportTicket close(UUID ticketId, UUID actorId) {
        SupportTicket ticket = requireTicket(ticketId);
        ticket.setClosedAt(Instant.now());
        return transitionStatus(ticket, SupportTicket.Status.CLOSED, actorId);
    }

    public SupportTicket reopen(UUID ticketId, UUID actorId) {
        SupportTicket ticket = requireTicket(ticketId);
        if (ticket.getStatus() != SupportTicket.Status.RESOLVED && ticket.getStatus() != SupportTicket.Status.CLOSED) {
            throw new IllegalStateException("Ticket " + ticketId + " is not RESOLVED/CLOSED — cannot reopen");
        }
        ticket.setResolvedAt(null);
        ticket.setClosedAt(null);
        return transitionStatus(ticket, SupportTicket.Status.IN_PROGRESS, actorId);
    }

    private SupportTicket transitionStatus(SupportTicket ticket, SupportTicket.Status newStatus, UUID actorId) {
        String from = ticket.getStatus().name();
        ticket.setStatus(newStatus);
        SupportTicket saved = tickets.save(ticket);
        events.publishEvent(new SupportTicketStatusChangedEvent(ticket.getId(), actorId, null, from, newStatus.name()));
        return saved;
    }
}
