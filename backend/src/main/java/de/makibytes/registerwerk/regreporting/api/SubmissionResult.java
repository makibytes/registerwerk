package de.makibytes.registerwerk.regreporting.api;

public record SubmissionResult(String submissionRef, Status status, String errorMessage) {

    public enum Status {
        ACCEPTED,
        REJECTED,
        PENDING_ACKNOWLEDGEMENT
    }

    public static SubmissionResult accepted(String submissionRef) {
        return new SubmissionResult(submissionRef, Status.ACCEPTED, null);
    }

    public static SubmissionResult pending(String submissionRef) {
        return new SubmissionResult(submissionRef, Status.PENDING_ACKNOWLEDGEMENT, null);
    }

    public static SubmissionResult rejected(String reason) {
        return new SubmissionResult(null, Status.REJECTED, reason);
    }
}
