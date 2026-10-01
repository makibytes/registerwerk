package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.ObligationAcknowledgement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param acknowledgedObligations one entry per open obligation the operator decides to terminate
 *        despite (ids come from {@code GET /entities/{id}/offboarding-obligations} or the 409 body)
 */
public record TerminateEntityRequest(
        @NotBlank @Size(max = 2000) String reason,
        @Valid List<AcknowledgedObligation> acknowledgedObligations) {

    public TerminateEntityRequest(String reason) {
        this(reason, List.of());
    }

    public record AcknowledgedObligation(@NotNull String obligationId, @NotBlank @Size(max = 2000) String reason) {
        ObligationAcknowledgement toAcknowledgement() {
            return new ObligationAcknowledgement(obligationId, reason);
        }
    }

    public List<ObligationAcknowledgement> acknowledgements() {
        return acknowledgedObligations == null ? List.of()
                : acknowledgedObligations.stream().map(AcknowledgedObligation::toAcknowledgement).toList();
    }
}
