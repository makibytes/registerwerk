package de.makibytes.registerwerk.orgidentity.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface KycChainPropagationRepository extends JpaRepository<KycChainPropagation, UUID> {

    Optional<KycChainPropagation> findByLegalEntityIdAndChainConfigId(UUID legalEntityId, UUID chainConfigId);

    List<KycChainPropagation> findByStatusIn(Collection<KycChainPropagation.Status> statuses);

    long countByStatus(KycChainPropagation.Status status);
}
