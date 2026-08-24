package de.makibytes.registerwerk.finality.web.dto;

import de.makibytes.registerwerk.finality.api.CompensationOutcome;

/** Flattened, JSON-friendly view of a {@link CompensationOutcome} — the frontend doesn't need to
 *  deal with the sealed interface's four record shapes, just an outcome label plus detail text. */
public record RetryResultResponse(String outcome, String detail) {

    public static RetryResultResponse of(CompensationOutcome outcome) {
        return switch (outcome) {
            case CompensationOutcome.Compensated c -> new RetryResultResponse("COMPENSATED", c.detail());
            case CompensationOutcome.NotApplicable n -> new RetryResultResponse("NOT_APPLICABLE", n.reason());
            case CompensationOutcome.Failed f -> new RetryResultResponse("FAILED", f.reason());
            case CompensationOutcome.Irreversible i -> new RetryResultResponse("IRREVERSIBLE", i.remediationHint());
        };
    }
}
