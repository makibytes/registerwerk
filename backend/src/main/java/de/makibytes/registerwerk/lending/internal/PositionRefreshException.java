package de.makibytes.registerwerk.lending.internal;

/** A position's on-chain state could not be read; the cached row must be marked stale, never zeroed. */
class PositionRefreshException extends RuntimeException {
    PositionRefreshException(String message, Throwable cause) {
        super(message, cause);
    }
}
