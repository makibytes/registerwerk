package de.makibytes.registerwerk.support.internal;

import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.support.api.SupportTicket;
import de.makibytes.registerwerk.support.api.SupportTicketMessage;
import de.makibytes.registerwerk.support.api.SupportTicketMessageRepository;
import de.makibytes.registerwerk.support.api.SupportTicketRepository;
import de.makibytes.registerwerk.support.events.SupportTicketMessageAddedEvent;
import de.makibytes.registerwerk.support.web.SupportTicketAdminController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Support tickets Phase 7 (7A-09)")
class SupportTicketPhase7Test {

    private final SupportTicketRepository tickets = mock(SupportTicketRepository.class);
    private final SupportTicketMessageRepository messages = mock(SupportTicketMessageRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SupportTicketService service = new SupportTicketService(tickets, messages, events);
    private final UUID ticketId = UUID.randomUUID();

    private SupportTicket ticket(SupportTicket.Status status) {
        SupportTicket t = new SupportTicket();
        t.setStatus(status);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(t));
        when(tickets.save(any())).thenAnswer(i -> i.getArgument(0));
        return t;
    }

    @Test
    @DisplayName("addMessage is not open to the read-only AUDIT role")
    void addMessageExcludesAudit() throws Exception {
        var m = SupportTicketAdminController.class.getMethod("addMessage", UUID.class,
                de.makibytes.registerwerk.support.web.dto.AddMessageRequest.class,
                org.springframework.security.core.Authentication.class);
        PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
        assertThat(pre).isNotNull();
        assertThat(pre.value()).doesNotContain("AUDIT").contains("REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("an operator message emits an audit event without the body")
    void operatorMessageIsAudited() {
        ticket(SupportTicket.Status.OPEN);
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));
        service.addMessage(ticketId, UUID.randomUUID(), true, "REGISTRY_ADMIN", "hello customer");
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SupportTicketMessageAddedEvent.class);
        assertThat(((SupportTicketMessageAddedEvent) captor.getValue()).payload().toString()).doesNotContain("hello");
    }

    @Test
    @DisplayName("a customer reply on a CLOSED ticket is rejected and nothing is stored")
    void customerReplyOnClosedRejected() {
        ticket(SupportTicket.Status.CLOSED);
        assertThatThrownBy(() -> service.addMessage(ticketId, UUID.randomUUID(), false, "hi"))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(messages, never()).save(any(SupportTicketMessage.class));
    }

    @Test
    @DisplayName("resolve on a CLOSED ticket is rejected; close on a CLOSED ticket is rejected")
    void resolveAndCloseGuards() {
        ticket(SupportTicket.Status.CLOSED);
        assertThatThrownBy(() -> service.resolve(ticketId, UUID.randomUUID(), "n"))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.close(ticketId, UUID.randomUUID()))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
