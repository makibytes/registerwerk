package de.makibytes.registerwerk.blockchain.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

/**
 * Tunables for {@link EvmContractService}'s immediate submission paths (Phase 4 K4a).
 *
 * <ul>
 *   <li>{@code immediate-submit-concurrency} - permits of the semaphore around immediate
 *       submit/send/deploy. Each such call holds the caller's pooled connection AND a second one
 *       inside {@code NonceCoordinator.withNonce}; unbounded, N concurrent calls deadlock a pool
 *       of fewer than 2N. 0 (default) = {@code max(1, hikari.maximum-pool-size / 4)}.</li>
 *   <li>{@code fee-cap.*} - global default ceilings, used when {@code chain_config} has no
 *       override (interim decision T4-02).</li>
 * </ul>
 */
@Component
public class EvmSubmissionSettings {

    private static final BigInteger GWEI = BigInteger.valueOf(1_000_000_000L);

    private final int immediateSubmitPermits;
    private final long acquireTimeoutMs;
    private final BigInteger defaultMaxFeePerGasWei;
    private final BigInteger defaultMaxPriorityFeePerGasWei;
    private final BigInteger maxGasLimit;

    public EvmSubmissionSettings(
            @Value("${registerwerk.blockchain.immediate-submit-concurrency:0}") int configuredPermits,
            @Value("${spring.datasource.hikari.maximum-pool-size:20}") int poolSize,
            @Value("${registerwerk.blockchain.immediate-submit-acquire-timeout-ms:10000}") long acquireTimeoutMs,
            @Value("${registerwerk.blockchain.fee-cap.default-max-fee-gwei:500}") long maxFeeGwei,
            @Value("${registerwerk.blockchain.fee-cap.default-max-tip-gwei:50}") long maxTipGwei,
            @Value("${registerwerk.blockchain.fee-cap.max-gas-limit:30000000}") long maxGasLimit) {
        this.immediateSubmitPermits = configuredPermits > 0 ? configuredPermits : Math.max(1, poolSize / 4);
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.defaultMaxFeePerGasWei = GWEI.multiply(BigInteger.valueOf(maxFeeGwei));
        this.defaultMaxPriorityFeePerGasWei = GWEI.multiply(BigInteger.valueOf(maxTipGwei));
        this.maxGasLimit = BigInteger.valueOf(maxGasLimit);
    }

    /** Production defaults (500 gwei / 50 gwei / 30M gas, 5 permits). */
    public static EvmSubmissionSettings defaults() {
        return new EvmSubmissionSettings(0, 20, 10_000, 500, 50, 30_000_000);
    }

    public int immediateSubmitPermits() { return immediateSubmitPermits; }
    public long acquireTimeoutMs() { return acquireTimeoutMs; }
    public BigInteger defaultMaxFeePerGasWei() { return defaultMaxFeePerGasWei; }
    public BigInteger defaultMaxPriorityFeePerGasWei() { return defaultMaxPriorityFeePerGasWei; }
    public BigInteger maxGasLimit() { return maxGasLimit; }
}
