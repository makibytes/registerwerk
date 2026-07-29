package de.makibytes.registerwerk.regreporting.api;

public record SubmissionResult(String transportRef, Status status, String errorMessage) {

    public enum Status {
        /** Bytes were written to the configured transport; no authority receipt was verified. */
        TRANSPORTED_UNVERIFIED,
        /** No transport was attempted (for example, the development NOOP adapter). */
        NOT_TRANSPORTED,
        /** The transport attempt failed; this says nothing about authority validation. */
        TRANSPORT_FAILED
    }

    public static SubmissionResult transportedUnverified(String transportRef) {
        return new SubmissionResult(transportRef, Status.TRANSPORTED_UNVERIFIED, null);
    }

    public static SubmissionResult notTransported(String reason) {
        return new SubmissionResult(null, Status.NOT_TRANSPORTED, reason);
    }

    public static SubmissionResult transportFailed(String reason) {
        return new SubmissionResult(null, Status.TRANSPORT_FAILED, reason);
    }
}
