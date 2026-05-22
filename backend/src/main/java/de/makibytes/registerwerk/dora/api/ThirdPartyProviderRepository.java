package de.makibytes.registerwerk.dora.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ThirdPartyProviderRepository extends JpaRepository<ThirdPartyProvider, UUID> {

    List<ThirdPartyProvider> findByCriticalityOrderByNameAsc(ThirdPartyProvider.Criticality criticality);

    List<ThirdPartyProvider> findByContractEndBeforeOrderByContractEndAsc(LocalDate threshold);
}
