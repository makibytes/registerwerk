package de.makibytes.registerwerk.payment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRailRepository extends JpaRepository<PaymentRail, UUID> {

    Optional<PaymentRail> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndEnabledTrue(String code);

    List<PaymentRail> findByEnabledTrueOrderByCodeAsc();

    List<PaymentRail> findAllByOrderByCodeAsc();
}
