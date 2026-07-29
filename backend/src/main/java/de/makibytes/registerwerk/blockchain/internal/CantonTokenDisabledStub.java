package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.CantonTokenOperations;
import de.makibytes.registerwerk.chain.api.Network;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * No-op fallback registered when the {@code canton} Maven profile is not active
 * (i.e. {@code CantonTokenService} is not on the classpath).
 */
@Service
@ConditionalOnMissingClass("de.makibytes.registerwerk.blockchain.api.CantonTokenService")
class CantonTokenDisabledStub implements CantonTokenOperations {

    private static CompletableFuture<String> disabled() {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Canton is not enabled. Rebuild with -Pcanton to activate Canton support."));
    }

    @Override public CompletableFuture<String> createInstrument(UUID a, Network n, String p, int d, UUID actorId, String actorRole) { return disabled(); }
    @Override public CompletableFuture<String> issue(UUID dep, String r, BigDecimal amt, UUID actorId, String actorRole) { return disabled(); }
    @Override public CompletableFuture<String> transfer(UUID dep, String h, String f, String t, BigDecimal amt, UUID actorId, String actorRole) { return disabled(); }
    @Override public CompletableFuture<String> forceTransfer(UUID dep, String h, String t, BigDecimal amt, String r, UUID actorId, String actorRole) { return disabled(); }
    @Override public CompletableFuture<String> freezeHolding(UUID dep, String h, UUID actorId, String actorRole) { return disabled(); }
    @Override public CompletableFuture<String> unfreezeHolding(UUID dep, String h, UUID actorId, String actorRole) { return disabled(); }
    @Override public CompletableFuture<String> burn(UUID dep, String h, BigDecimal amt, UUID actorId, String actorRole) { return disabled(); }
    @Override public CompletableFuture<String> pauseInstrument(UUID dep, UUID actorId, String actorRole) { return disabled(); }
    @Override public CompletableFuture<String> unpauseInstrument(UUID dep, UUID actorId, String actorRole) { return disabled(); }
}
