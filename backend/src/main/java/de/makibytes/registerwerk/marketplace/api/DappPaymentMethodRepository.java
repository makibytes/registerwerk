package de.makibytes.registerwerk.marketplace.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DappPaymentMethodRepository extends JpaRepository<DappPaymentMethod, UUID> {

    List<DappPaymentMethod> findByVersionId(UUID versionId);

    void deleteByVersionId(UUID versionId);

    List<DappPaymentMethod> findByRailCode(String railCode);
}
