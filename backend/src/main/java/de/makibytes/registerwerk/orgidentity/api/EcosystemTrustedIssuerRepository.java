package de.makibytes.registerwerk.orgidentity.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EcosystemTrustedIssuerRepository extends JpaRepository<EcosystemTrustedIssuer, UUID> {

    List<EcosystemTrustedIssuer> findByChainConfigIdOrderByCreatedAtDesc(UUID chainConfigId);

    List<EcosystemTrustedIssuer> findAllByOrderByCreatedAtDesc();

    List<EcosystemTrustedIssuer> findByStatus(MemberWalletStatus status);

    List<EcosystemTrustedIssuer> findByStatusAndRemovedTxIsNull(MemberWalletStatus status);
}
