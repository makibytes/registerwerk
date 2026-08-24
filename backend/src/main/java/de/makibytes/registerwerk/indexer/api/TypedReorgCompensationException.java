package de.makibytes.registerwerk.indexer.api;

/**
 * Signals that indexer materialization for a typed routine reorg could not be compensated.
 * The application transaction is rolled back; the durable-stream boundary catches this exact
 * signal and persists a chain quarantine before acknowledging the episode.
 */
public class TypedReorgCompensationException extends RuntimeException {

    public TypedReorgCompensationException(String message) {
        super(message);
    }
}
