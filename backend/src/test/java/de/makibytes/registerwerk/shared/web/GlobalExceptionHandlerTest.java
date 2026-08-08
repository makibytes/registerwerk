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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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

    @Test
    @DisplayName("an invalid path-variable type is reported as a 400 without echoing its value")
    void handleTypeMismatch_returnsSafeBadRequest() {
        when(request.getRequestURI()).thenReturn("/api/v1/assets/not-a-uuid");
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "id", null, new IllegalArgumentException("conversion failed"));

        ResponseEntity<?> response = handler.handleTypeMismatch(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).extracting("message").isEqualTo("Invalid value for parameter 'id'");
    }

    @Test
    @DisplayName("malformed JSON is reported as a 400 instead of falling through to a 500")
    void handleUnreadableMessage_returnsBadRequest() {
        when(request.getRequestURI()).thenReturn("/api/v1/assets");
        MockHttpInputMessage input = new MockHttpInputMessage("{".getBytes(StandardCharsets.UTF_8));
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException("bad JSON", input);

        ResponseEntity<?> response = handler.handleUnreadableMessage(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).extracting("message").isEqualTo("Request body is missing or malformed");
    }

    @Test
    @DisplayName("an unsupported HTTP method is reported as 405")
    void handleMethodNotSupported_returnsMethodNotAllowed() {
        when(request.getMethod()).thenReturn(HttpMethod.TRACE.name());
        when(request.getRequestURI()).thenReturn("/api/v1/assets");
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("TRACE", List.of("GET"));

        ResponseEntity<?> response = handler.handleMethodNotSupported(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
