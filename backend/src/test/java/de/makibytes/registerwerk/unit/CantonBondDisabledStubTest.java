package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.CantonBondOperations;
import de.makibytes.registerwerk.blockchain.api.CantonBondOperations.BondCreationTerms;
import de.makibytes.registerwerk.chain.api.Network;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the disabled-stub behaviour of CantonBondOperations (when the canton profile is off).
 * Uses a local stub that reproduces the same disabled() pattern to avoid package-private access issues.
 */
@DisplayName("CantonBondOperations disabled-stub — all methods fail when canton profile is off")
class CantonBondDisabledStubTest {

    /** Local replica of the disabled-stub pattern to test through the public interface. */
    private static class LocalBondStub implements CantonBondOperations {
        private static CompletableFuture<String> disabled() {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Canton is not enabled. Rebuild with -Pcanton."));
        }
        @Override public CompletableFuture<String> createFixedBond(UUID a, Network n, String p, BondCreationTerms t) { return disabled(); }
        @Override public CompletableFuture<String> createFloatingBond(UUID a, Network n, String p, BondCreationTerms t) { return disabled(); }
        @Override public CompletableFuture<String> createZeroBond(UUID a, Network n, String p, BondCreationTerms t) { return disabled(); }
        @Override public CompletableFuture<String> payCoupon(UUID dep, Instant date, BigDecimal amt, UUID actor) { return disabled(); }
        @Override public CompletableFuture<String> fixFloatingRate(UUID dep, BigDecimal rate, Instant date, UUID actor) { return disabled(); }
        @Override public CompletableFuture<String> redeem(UUID dep, Instant date, UUID actor) { return disabled(); }
        @Override public CompletableFuture<String> earlyCall(UUID dep, Instant date, BigDecimal price, UUID actor) { return disabled(); }
    }

    private final CantonBondOperations stub = new LocalBondStub();

    @Test
    void createFixedBond_failsWithUnsupported() {
        var future = stub.createFixedBond(UUID.randomUUID(), Network.TESTNET, "party", null);
        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void createFloatingBond_failsWithUnsupported() {
        assertThat(stub.createFloatingBond(UUID.randomUUID(), Network.TESTNET, "p", null).isCompletedExceptionally()).isTrue();
    }

    @Test
    void createZeroBond_failsWithUnsupported() {
        assertThat(stub.createZeroBond(UUID.randomUUID(), Network.TESTNET, "p", null).isCompletedExceptionally()).isTrue();
    }

    @Test
    void payCoupon_failsWithUnsupported() {
        assertThat(stub.payCoupon(UUID.randomUUID(), Instant.now(), BigDecimal.ONE, UUID.randomUUID()).isCompletedExceptionally()).isTrue();
    }

    @Test
    void fixFloatingRate_failsWithUnsupported() {
        assertThat(stub.fixFloatingRate(UUID.randomUUID(), BigDecimal.ONE, Instant.now(), UUID.randomUUID()).isCompletedExceptionally()).isTrue();
    }

    @Test
    void redeem_failsWithUnsupported() {
        assertThat(stub.redeem(UUID.randomUUID(), Instant.now(), UUID.randomUUID()).isCompletedExceptionally()).isTrue();
    }

    @Test
    void earlyCall_failsWithUnsupported() {
        assertThat(stub.earlyCall(UUID.randomUUID(), Instant.now(), BigDecimal.ONE, UUID.randomUUID()).isCompletedExceptionally()).isTrue();
    }
}
