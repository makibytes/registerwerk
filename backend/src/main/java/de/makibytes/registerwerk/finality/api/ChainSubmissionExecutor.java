package de.makibytes.registerwerk.finality.api;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs an irreversible chain write behind the chain-quarantine linearization lock.
 *
 * <p>This boundary is for stacks that do not yet have a signed-payload outbox. The callback
 * executes in the same database transaction that checks quarantine and locks the chain row, so
 * quarantine activation cannot race between the safety check and submission.
 */
public interface ChainSubmissionExecutor {

    <T> T execute(UUID chainConfigId, Supplier<T> submission);
}
