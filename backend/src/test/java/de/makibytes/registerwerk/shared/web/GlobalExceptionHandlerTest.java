package de.makibytes.registerwerk.shared.web;

import de.makibytes.registerwerk.shared.ComplianceGateException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies Phase 5 finding #11: compliance-gate rejections ({@link ComplianceGateException})
 * are recorded as rejected actions, while the many unrelated {@link IllegalStateException}
 * call sites elsewhere in the codebase (config/infra errors) deliberately are not.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler unit tests")
class GlobalExceptionHandlerTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(eventPublisher);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a ComplianceGateException on a mutating request is recorded as a rejected action")
    void handleComplianceGate_recordsRejectionForMutatingRequest() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/kyc/approve");

        ResponseEntity<?> response = handler.handleComplianceGate(
                new ComplianceGateException("KYC blocked: unresolved sanctions hit"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ArgumentCaptor<RejectedActionEvent> captor = ArgumentCaptor.forClass(RejectedActionEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("COMPLIANCE_GATE_BLOCKED");
        assertThat(captor.getValue().reason()).contains("unresolved sanctions hit");
    }

    @Test
    @DisplayName("a ComplianceGateException on a GET request is not recorded (routine read, not a mutating attempt)")
    void handleComplianceGate_doesNotRecordForGetRequest() {
        when(request.getMethod()).thenReturn("GET");

        handler.handleComplianceGate(new ComplianceGateException("blocked"), request);

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a plain IllegalStateException (unrelated config/infra error) is NOT recorded as a rejected action")
    void handleIllegalState_doesNotRecordRejection() {
        ResponseEntity<?> response = handler.handleIllegalState(
                new IllegalStateException("SHA-256 unavailable"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
