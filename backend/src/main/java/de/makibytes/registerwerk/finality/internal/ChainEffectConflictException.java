package de.makibytes.registerwerk.finality.internal;

/**
 * Raised when an idempotency key already belongs to a semantically different chain effect.
 * Returning the existing row in that situation would silently discard new compensation
 * provenance and make a later reorg produce the wrong business state.
 */
final class ChainEffectConflictException extends IllegalStateException {

    ChainEffectConflictException(String message) {
        super(message);
    }
}
