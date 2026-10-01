package de.makibytes.registerwerk.dora.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An incident missed a reporting deadline. Not an audit event (the breach is derived data, re-evaluated
 * every run); consumed by the notification module to alert the registry administrators.
 *
 * @param breachType classification | initial_report | intermediate_report | final_report
 */
public record IctIncidentDeadlineBreachedEvent(UUID incidentId, String title, String breachType, Instant deadline) {
}
