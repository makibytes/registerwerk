package de.makibytes.registerwerk.payment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentRailChainAddressRepository extends JpaRepository<PaymentRailChainAddress, UUID> {

    List<PaymentRailChainAddress> findByPaymentRailId(UUID paymentRailId);

    boolean existsByPaymentRailIdAndChainConfigId(UUID paymentRailId, UUID chainConfigId);

    void deleteByPaymentRailId(UUID paymentRailId);
}
