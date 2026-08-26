package de.makibytes.registerwerk.blockchain.internal;

/** Fail-stop signal for an immutable-event conflict or a gap in Chaincache's lifecycle stream. */
final class ChaincacheProtocolException extends IllegalStateException {
    ChaincacheProtocolException(String message) {
        super(message);
    }
}

