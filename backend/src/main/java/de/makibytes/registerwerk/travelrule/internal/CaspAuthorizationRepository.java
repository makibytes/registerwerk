package de.makibytes.registerwerk.travelrule.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface CaspAuthorizationRepository extends JpaRepository<CaspAuthorization, UUID> {

    Optional<CaspAuthorization> findByVaspDidIgnoreCase(String vaspDid);
}
