package de.makibytes.registerwerk.support.internal;

import de.makibytes.registerwerk.support.api.SupportTicket;
import de.makibytes.registerwerk.support.api.SupportTicketMessageRepository;
import de.makibytes.registerwerk.support.api.SupportTicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SupportTicketService unit tests")
class SupportTicketServiceTest {

    @Mock private SupportTicketRepository tickets;
    @Mock private SupportTicketMessageRepository messages;
    @Mock private ApplicationEventPublisher events;

    @InjectMocks
    private SupportTicketService service;

    @Test
    @DisplayName("create persists an OPEN ticket and publishes SupportTicketCreatedEvent")
    void create_persistsOpenTicket() {
        UUID entityId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupportTicket ticket = service.create(entityId, userId, "Can't see my holdings", "Details here",
                SupportTicket.Category.TECHNICAL, null);

        assertThat(ticket.getStatus()).isEqualTo(SupportTicket.Status.OPEN);
        assertThat(ticket.getPriority()).isEqualTo(SupportTicket.Priority.NORMAL); // defaulted
        verify(events).publishEvent(any(de.makibytes.registerwerk.support.events.SupportTicketCreatedEvent.class));
    }

    @Test
    @DisplayName("requireOwnedTicket throws for a ticket belonging to a different entity")
    void requireOwnedTicket_rejectsWrongEntity() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = new SupportTicket();
        ticket.setEntityId(UUID.randomUUID());
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.requireOwnedTicket(ticketId, UUID.randomUUID()))
                .isInstanceOf(de.makibytes.registerwerk.shared.EntityNotFoundException.class);
    }

    @Test
    @DisplayName("a customer reply reopens a RESOLVED ticket to IN_PROGRESS")
    void addMessage_customerReplyReopensResolvedTicket() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = new SupportTicket();
        ticket.setStatus(SupportTicket.Status.RESOLVED);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addMessage(ticketId, UUID.randomUUID(), false, "Still not working");

        assertThat(ticket.getStatus()).isEqualTo(SupportTicket.Status.IN_PROGRESS);
    }

    @Test
    @DisplayName("an operator reply does NOT reopen a RESOLVED ticket")
    void addMessage_operatorReplyDoesNotReopen() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = new SupportTicket();
        ticket.setStatus(SupportTicket.Status.RESOLVED);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addMessage(ticketId, UUID.randomUUID(), true, "Following up");

        assertThat(ticket.getStatus()).isEqualTo(SupportTicket.Status.RESOLVED);
    }

    @Test
    @DisplayName("reopen rejects a ticket that is not RESOLVED/CLOSED")
    void reopen_rejectsOpenTicket() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = new SupportTicket();
        ticket.setStatus(SupportTicket.Status.OPEN);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.reopen(ticketId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("assign moves an OPEN ticket to IN_PROGRESS")
    void assign_movesOpenTicketToInProgress() {
        UUID ticketId = UUID.randomUUID();
        SupportTicket ticket = new SupportTicket();
        ticket.setStatus(SupportTicket.Status.OPEN);
        UUID assignee = UUID.randomUUID();
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupportTicket result = service.assign(ticketId, assignee, UUID.randomUUID());

        assertThat(result.getAssignedTo()).isEqualTo(assignee);
        assertThat(result.getStatus()).isEqualTo(SupportTicket.Status.IN_PROGRESS);
    }
}
