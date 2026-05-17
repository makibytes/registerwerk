package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.asset.api.MintControlRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MintControlRuleRepository extends JpaRepository<MintControlRule, UUID> {

    List<MintControlRule> findByAssetDeploymentIdAndActive(UUID deploymentId, boolean active);

    Optional<MintControlRule> findByAssetDeploymentIdAndTargetAddress(UUID deploymentId, String targetAddress);
}
