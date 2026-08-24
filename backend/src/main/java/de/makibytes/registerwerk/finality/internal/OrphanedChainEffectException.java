package de.makibytes.registerwerk.finality.internal;

/** A delayed forward event tried to journal state from a block already known to be orphaned. */
final class OrphanedChainEffectException extends IllegalStateException {

    OrphanedChainEffectException(String message) {
        super(message);
    }
}
