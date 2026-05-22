package de.makibytes.registerwerk.kyc.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface NaturalPersonRepository extends JpaRepository<NaturalPerson, UUID> {

    @Query("SELECT n FROM NaturalPerson n WHERE n.redacted = false AND n.pepStatus IN ('DOMESTIC_PEP','FOREIGN_PEP','INTERNATIONAL_PEP','PEP_FAMILY','PEP_ASSOCIATE')")
    List<NaturalPerson> findActivePeps();
}
