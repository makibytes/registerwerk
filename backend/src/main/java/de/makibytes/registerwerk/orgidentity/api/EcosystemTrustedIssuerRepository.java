package de.makibytes.registerwerk.orgidentity.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EcosystemTrustedIssuerRepository extends JpaRepository<EcosystemTrustedIssuer, UUID> {

    List<EcosystemTrustedIssuer> findByChainConfigIdOrderByCreatedAtDesc(UUID chainConfigId);

    List<EcosystemTrustedIssuer> findAllByOrderByCreatedAtDesc();

    List<EcosystemTrustedIssuer> findByStatus(TrustedIssuerStatus status);

    /** Addition or retiring predecessor that may still exist on-chain. */
    @Query("""
            SELECT i FROM EcosystemTrustedIssuer i
            WHERE i.chainConfigId = :chainConfigId
              AND lower(i.issuerAddress) = lower(:issuerAddress)
              AND i.status IN (de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus.PENDING,
                               de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus.ACTIVE,
                               de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus.REMOVAL_PENDING,
                               de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus.REMOVAL_FAILED)
            """)
    Optional<EcosystemTrustedIssuer> findLiveIssuer(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("issuerAddress") String issuerAddress);
}
