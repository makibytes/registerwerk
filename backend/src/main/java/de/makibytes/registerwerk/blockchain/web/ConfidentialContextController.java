package de.makibytes.registerwerk.blockchain.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.makibytes.registerwerk.blockchain.web.dto.ConfidentialContextResponse;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.shared.EntityNotFoundException;

/**
 * Returns the minimal chain/contract info a frontend's {@code FheClientService} needs to
 * read + decrypt a confidential balance directly against Zama's relayer — see
 * {@link ConfidentialContextResponse}'s class-level note on why nothing else is served here.
 *
 * <p>Access is deliberately broader than the plaintext {@code DeploymentController}: a holder who
 * merely INVESTS in (not issues) a confidential asset still needs this to reveal their own
 * balance, which {@code AssetAccessChecker.isHolderOfAsset} exists specifically to allow without
 * widening the shared {@code canRead} check used by unrelated endpoints.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetId}/deployments/{depId}/confidential-context")
@PreAuthorize("hasRole('REGISTRY_ADMIN') or hasRole('AUDIT') "
        + "or @assetAccessChecker.canActAsIssuer(#assetId, authentication) "
        + "or @assetAccessChecker.isHolderOfAsset(#assetId, authentication)")
public class ConfidentialContextController {

    private final AssetDeploymentRepository deploymentRepository;
    private final AssetLookupPort assetLookupPort;
    private final ChainConfigRepository chainConfigRepository;

    public ConfidentialContextController(
            AssetDeploymentRepository deploymentRepository,
            AssetLookupPort assetLookupPort,
            ChainConfigRepository chainConfigRepository) {
        this.deploymentRepository = deploymentRepository;
        this.assetLookupPort = assetLookupPort;
        this.chainConfigRepository = chainConfigRepository;
    }

    @GetMapping
    public ResponseEntity<ConfidentialContextResponse> context(
            @PathVariable UUID assetId, @PathVariable UUID depId) {
        AssetDeployment dep = deploymentRepository.findByIdAndAssetId(depId, assetId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", depId));

        AssetLookupPort.AssetInfo asset = assetLookupPort.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (asset.tokenStandard() != TokenStandard.CONF_ERC20 && asset.tokenStandard() != TokenStandard.CONF_ERC3643) {
            throw new IllegalArgumentException("confidential-context is only available for confidential token standards");
        }

        // Same "CHAIN_NETWORK" identifier convention WalletSigner.credentialsForDescriptor uses.
        String identifier = dep.getChain().name() + "_" + dep.getNetwork().name();
        ChainConfig chainConfig = chainConfigRepository.findByIdentifier(identifier)
                .orElseThrow(() -> new IllegalStateException("No ChainConfig configured for " + identifier));

        return ResponseEntity.ok(new ConfidentialContextResponse(
                dep.getContractAddress(), dep.getChain().name(), dep.getNetwork().name(), chainConfig.getChainId()));
    }
}
