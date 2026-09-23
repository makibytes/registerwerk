package de.makibytes.registerwerk.asset.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GasSponsorshipVoucherRepository extends JpaRepository<GasSponsorshipVoucher, UUID> {

    /** Σ max_cost_wei of the policy's vouchers issued since {@code since} (null when none). */
    @Query("SELECT SUM(v.maxCostWei) FROM GasSponsorshipVoucher v "
            + "WHERE v.policyId = :policyId AND v.createdAt >= :since")
    BigInteger sumMaxCostSince(@Param("policyId") UUID policyId, @Param("since") Instant since);

    /** Σ max_cost_wei of one entity's vouchers under the policy since {@code since} (null when none). */
    @Query("SELECT SUM(v.maxCostWei) FROM GasSponsorshipVoucher v "
            + "WHERE v.policyId = :policyId AND v.entityId = :entityId AND v.createdAt >= :since")
    BigInteger sumMaxCostSinceForEntity(@Param("policyId") UUID policyId, @Param("entityId") UUID entityId,
                                        @Param("since") Instant since);

    /** The voucher already issued for this UserOperation nonce, if any (unique per V9). */
    Optional<GasSponsorshipVoucher> findByPolicyIdAndSenderAndUserOpNonce(UUID policyId, String sender, BigInteger userOpNonce);
}
