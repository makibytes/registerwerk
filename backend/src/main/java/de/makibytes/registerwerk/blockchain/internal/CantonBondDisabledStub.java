package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.CantonBondOperations;
import de.makibytes.registerwerk.chain.api.Network;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * No-op fallback registered when the {@code canton} Maven profile is not active
 * (i.e. {@code CantonBondService} is not on the classpath).
 */
@Service
@ConditionalOnMissingClass("de.makibytes.registerwerk.blockchain.api.CantonBondService")
class CantonBondDisabledStub implements CantonBondOperations {

    private static CompletableFuture<String> disabled() {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Canton bond operations are not enabled. Rebuild with -Pcanton to activate Canton support."));
    }

    @Override public CompletableFuture<String> createFixedBond(UUID a, Network n, String p, BondCreationTerms t) { return disabled(); }
    @Override public CompletableFuture<String> createFloatingBond(UUID a, Network n, String p, BondCreationTerms t) { return disabled(); }
    @Override public CompletableFuture<String> createZeroBond(UUID a, Network n, String p, BondCreationTerms t) { return disabled(); }
    @Override public CompletableFuture<String> payCoupon(UUID dep, Instant date, BigDecimal amt, UUID actor) { return disabled(); }
    @Override public CompletableFuture<String> fixFloatingRate(UUID dep, BigDecimal rate, Instant date, UUID actor) { return disabled(); }
    @Override public CompletableFuture<String> redeem(UUID dep, Instant date, UUID actor) { return disabled(); }
    @Override public CompletableFuture<String> earlyCall(UUID dep, Instant date, BigDecimal price, UUID actor) { return disabled(); }
}
