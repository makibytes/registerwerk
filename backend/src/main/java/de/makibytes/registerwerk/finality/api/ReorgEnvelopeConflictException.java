package de.makibytes.registerwerk.finality.api;

/** A producer reused a durable reorg id for a semantically different immutable envelope. */
public class ReorgEnvelopeConflictException extends RuntimeException {
    public ReorgEnvelopeConflictException(String message) {
        super(message);
    }
}
