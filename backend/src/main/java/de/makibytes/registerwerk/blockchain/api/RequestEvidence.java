package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * P4C-4: reads the 4-eyes evidence of the current HTTP request (validated second approver, set by
 * {@code DualControlApproverInterceptor}; optional {@code X-Case-Reference} header) so that chain
 * operations and their audit events can record it without threading extra parameters through every
 * service signature. Returns null outside a request (scheduled jobs, retries).
 */
public final class RequestEvidence {

    public static final String CASE_REFERENCE_HEADER = "X-Case-Reference";
    private static final int MAX_CASE_REFERENCE = 200;

    private RequestEvidence() {}

    public static UUID approverId() {
        HttpServletRequest request = current();
        if (request != null && request.getAttribute(StepUpAttributes.DUAL_CONTROL_APPROVER_ID) instanceof UUID id) {
            return id;
        }
        return null;
    }

    /** Correlates the {@code DUAL_CONTROL_APPROVED} audit entry with the domain event. */
    public static UUID requestId() {
        HttpServletRequest request = current();
        if (request != null && request.getAttribute(StepUpAttributes.DUAL_CONTROL_REQUEST_ID) instanceof UUID id) {
            return id;
        }
        return null;
    }

    public static String caseReference() {
        HttpServletRequest request = current();
        String value = request != null ? request.getHeader(CASE_REFERENCE_HEADER) : null;
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > MAX_CASE_REFERENCE ? trimmed.substring(0, MAX_CASE_REFERENCE) : trimmed;
    }

    private static HttpServletRequest current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra
                ? sra.getRequest() : null;
    }
}
