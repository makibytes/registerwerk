package de.makibytes.registerwerk.shared;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }

    public InvalidStateTransitionException(String entityType, String fromState, String toState) {
        super("Invalid state transition for " + entityType + ": " + fromState + " → " + toState);
    }
}
