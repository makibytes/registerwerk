package de.makibytes.registerwerk.finality.web;

import de.makibytes.registerwerk.finality.api.FinalityDecision;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.FinalityNotReachedException;
import de.makibytes.registerwerk.finality.api.GatedOperation;
import de.makibytes.registerwerk.finality.web.dto.FinalityErrorResponse;
import de.makibytes.registerwerk.shared.events.RejectedActionEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinalityExceptionHandler — 409 response shape and rejected-action auditing")
class FinalityExceptionHandlerTest {

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private HttpServletRequest request;

    private FinalityExceptionHandler handler;
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new FinalityExceptionHandler(eventPublisher);
        SecurityContextHolder.clearContext();
    }

    private FinalityNotReachedException blockedException() {
        return new FinalityNotReachedException(new FinalityDecision.Blocked(
                GatedOperation.REGISTER_STATEMENT_ISSUE, assetId, FinalityLevel.FINALIZED, FinalityLevel.SAFE,
                FinalityDecision.Blocked.Reason.BELOW_REQUIRED, "Requires FINALIZED, currently SAFE."));
    }

    @Test
    @DisplayName("returns 409 with the full decision shape (operation/requiredLevel/currentLevel/reason)")
    void returns409WithDecisionShape() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/register-statements");

        ResponseEntity<FinalityErrorResponse> response = handler.handleFinalityNotReached(blockedException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        FinalityErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.operation()).isEqualTo("REGISTER_STATEMENT_ISSUE");
        assertThat(body.requiredLevel()).isEqualTo("FINALIZED");
        assertThat(body.currentLevel()).isEqualTo("SAFE");
        assertThat(body.reason()).isEqualTo("BELOW_REQUIRED");
        assertThat(body.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("a mutating request records a rejected-action audit event")
    void mutatingRequest_recordsRejection() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/register-statements");

        handler.handleFinalityNotReached(blockedException(), request);

        ArgumentCaptor<RejectedActionEvent> captor = ArgumentCaptor.forClass(RejectedActionEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("FINALITY_NOT_REACHED");
    }

    @Test
    @DisplayName("a GET request is not recorded (routine read, not a mutating attempt)")
    void getRequest_doesNotRecordRejection() {
        when(request.getMethod()).thenReturn("GET");

        handler.handleFinalityNotReached(blockedException(), request);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
