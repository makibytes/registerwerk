package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.asset.MintControlRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MintControlRuleRepository extends JpaRepository<MintControlRule, UUID> {

    List<MintControlRule> findByAssetDeploymentIdAndActive(UUID deploymentId, boolean active);

    Optional<MintControlRule> findByAssetDeploymentIdAndTargetAddress(UUID deploymentId, String targetAddress);
}
