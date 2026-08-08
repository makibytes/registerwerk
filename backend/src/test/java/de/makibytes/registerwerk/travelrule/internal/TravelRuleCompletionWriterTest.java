package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.events.TravelRuleMessageSentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TravelRuleCompletionWriterTest {

    @Mock JdbcTemplate jdbc;
    @Mock ApplicationEventPublisher events;

    @Test
    void lateSuccessDoesNotPublishDuplicateEvent() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        TravelRuleCompletionWriter writer = new TravelRuleCompletionWriter(jdbc, events);

        writer.markSent(UUID.randomUUID(), "TRP", "protocol-id", "vasp-id");

        verify(events, never()).publishEvent(any());
    }

    @Test
    void pendingMessageTransitionPublishesEvent() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID messageId = UUID.randomUUID();
        TravelRuleCompletionWriter writer = new TravelRuleCompletionWriter(jdbc, events);

        writer.markSent(messageId, "TRP", "protocol-id", "vasp-id");

        ArgumentCaptor<TravelRuleMessageSentEvent> event =
                ArgumentCaptor.forClass(TravelRuleMessageSentEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().messageId()).isEqualTo(messageId);
    }
}
