package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.chain.api.Network;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API contract for Canton DAML Finance bond instrument operations.
 * Decouples callers from the DAML-backed implementation so they compile
 * without the {@code canton} Maven profile.
 *
 * <p>When the {@code canton} profile is not active, {@code CantonBondDisabledStub}
 * is registered; all methods throw {@link UnsupportedOperationException}.
 */
public interface CantonBondOperations {

    /**
     * Parameters for creating a bond instrument.
     *
     * @param faceValue      nominal value per unit
     * @param currencyIso    ISO-4217 currency code
     * @param issueDate      issue date (ISO-8601 string, e.g. "2025-06-01")
     * @param maturityDate   maturity date
     * @param couponRate     annual coupon rate for fixed bonds (null for zero-coupon)
     * @param referenceRate  reference rate code for floating bonds (e.g. "EURIBOR_3M"); null for fixed/zero
     * @param spread         spread over reference rate for floating bonds; null for fixed/zero
     * @param dayCount       day-count convention (e.g. "ACT_360")
     * @param paymentFrequency payment frequency (e.g. "SEMI_ANNUAL", "ZERO")
     * @param callable       whether the bond is callable before maturity
     */
    record BondCreationTerms(
            BigDecimal faceValue,
            String currencyIso,
            String issueDate,
            String maturityDate,
            BigDecimal couponRate,
            String referenceRate,
            BigDecimal spread,
            String dayCount,
            String paymentFrequency,
            boolean callable
    ) {}

    CompletableFuture<String> createFixedBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms);

    CompletableFuture<String> createFloatingBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms);

    CompletableFuture<String> createZeroBond(
            UUID assetId, Network network, String issuerPartyId, BondCreationTerms terms);

    CompletableFuture<String> payCoupon(
            UUID deploymentId, Instant paymentDate, BigDecimal amountPerUnit, UUID actorId);

    CompletableFuture<String> fixFloatingRate(
            UUID deploymentId, BigDecimal rate, Instant fixingDate, UUID actorId);

    CompletableFuture<String> redeem(UUID deploymentId, Instant maturityDate, UUID actorId);

    CompletableFuture<String> earlyCall(
            UUID deploymentId, Instant callDate, BigDecimal callPrice, UUID actorId);
}
