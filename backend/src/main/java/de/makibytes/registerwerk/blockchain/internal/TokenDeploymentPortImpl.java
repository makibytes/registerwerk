package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.CantonBondOperations;
import de.makibytes.registerwerk.blockchain.api.CantonTokenOperations;
import de.makibytes.registerwerk.blockchain.api.TokenDeploymentPort;
import de.makibytes.registerwerk.blockchain.internal.confidential.ConfidentialErc20Service;
import de.makibytes.registerwerk.blockchain.internal.deploy.Erc1155DeploymentService;
import de.makibytes.registerwerk.blockchain.internal.deploy.Erc20DeploymentService;
import de.makibytes.registerwerk.blockchain.internal.deploy.Erc3525DeploymentService;
import de.makibytes.registerwerk.blockchain.internal.deploy.Erc4626DeploymentService;
import de.makibytes.registerwerk.blockchain.internal.deploy.Erc7540DeploymentService;
import de.makibytes.registerwerk.blockchain.internal.deploy.Erc721DeploymentService;
import de.makibytes.registerwerk.blockchain.internal.deploy.SolanaTokenService;
import de.makibytes.registerwerk.blockchain.internal.deploy.SplExtensionSet;
import de.makibytes.registerwerk.blockchain.internal.deploy.StarknetTokenService;
import de.makibytes.registerwerk.blockchain.internal.deploy.StellarAssetService;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.Network;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Dispatches token deployment requests to the appropriate chain/standard-specific service.
 * This implementation lives in {@code blockchain.internal} so it can access all deployment
 * services without violating module boundaries.
 */
@Service
public class TokenDeploymentPortImpl implements TokenDeploymentPort {

    private final Erc20DeploymentService erc20DeploymentService;
    private final Erc721DeploymentService erc721DeploymentService;
    private final Erc1155DeploymentService erc1155DeploymentService;
    private final Erc3525DeploymentService erc3525DeploymentService;
    private final Erc4626DeploymentService erc4626DeploymentService;
    private final Erc7540DeploymentService erc7540DeploymentService;
    private final ConfidentialErc20Service confidentialErc20Service;
    private final SolanaTokenService solanaTokenService;
    private final CantonTokenOperations cantonTokenOperations;
    private final CantonBondOperations cantonBondOperations;
    private final StarknetTokenService starknetTokenService;
    private final StellarAssetService stellarAssetService;

    public TokenDeploymentPortImpl(
            Erc20DeploymentService erc20DeploymentService,
            Erc721DeploymentService erc721DeploymentService,
            Erc1155DeploymentService erc1155DeploymentService,
            Erc3525DeploymentService erc3525DeploymentService,
            Erc4626DeploymentService erc4626DeploymentService,
            Erc7540DeploymentService erc7540DeploymentService,
            ConfidentialErc20Service confidentialErc20Service,
            SolanaTokenService solanaTokenService,
            CantonTokenOperations cantonTokenOperations,
            CantonBondOperations cantonBondOperations,
            StarknetTokenService starknetTokenService,
            StellarAssetService stellarAssetService) {
        this.erc20DeploymentService = erc20DeploymentService;
        this.erc721DeploymentService = erc721DeploymentService;
        this.erc1155DeploymentService = erc1155DeploymentService;
        this.erc3525DeploymentService = erc3525DeploymentService;
        this.erc4626DeploymentService = erc4626DeploymentService;
        this.erc7540DeploymentService = erc7540DeploymentService;
        this.confidentialErc20Service = confidentialErc20Service;
        this.solanaTokenService = solanaTokenService;
        this.cantonTokenOperations = cantonTokenOperations;
        this.cantonBondOperations = cantonBondOperations;
        this.starknetTokenService = starknetTokenService;
        this.stellarAssetService = stellarAssetService;
    }

    @Override
    public CompletableFuture<String> deploy(
            UUID assetId, TokenStandard standard, Chain chain, Network network, String ownerAddress) {

        ChainDescriptor descriptor = new ChainDescriptor(chain, network);

        if (chain == Chain.SOLANA) {
            return switch (standard) {
                case SPL -> solanaTokenService.createSplToken(assetId, network, ownerAddress);
                case SPL_2022 -> solanaTokenService.createSplToken2022(assetId, network, ownerAddress);
                case SPL_2022_BOND -> solanaTokenService.createSplToken2022(assetId, network, ownerAddress, SplExtensionSet.BOND);
                case SPL_2022_CONFIDENTIAL -> solanaTokenService.createSplToken2022(assetId, network, ownerAddress, SplExtensionSet.CONFIDENTIAL);
                default -> throw new UnsupportedOperationException("Solana does not support token standard: " + standard);
            };
        }

        if (chain == Chain.CANTON) {
            return switch (standard) {
                case CANTON_TOKEN -> cantonTokenOperations.createInstrument(assetId, network, ownerAddress, 0);
                case DAML_BOND_FIXED -> cantonBondOperations.createFixedBond(assetId, network, ownerAddress, null);
                case DAML_BOND_FLOATING -> cantonBondOperations.createFloatingBond(assetId, network, ownerAddress, null);
                case DAML_BOND_ZERO -> cantonBondOperations.createZeroBond(assetId, network, ownerAddress, null);
                default -> throw new UnsupportedOperationException("Canton does not support token standard: " + standard);
            };
        }

        if (chain == Chain.STARKNET) {
            return switch (standard) {
                case STARKNET_ERC20 -> starknetTokenService.createCairoErc20(assetId, network, ownerAddress);
                case STARKNET_ERC3525 -> starknetTokenService.createCairoErc3525(assetId, network, ownerAddress);
                default -> throw new UnsupportedOperationException("Starknet does not support token standard: " + standard);
            };
        }

        if (chain == Chain.STELLAR) {
            return switch (standard) {
                case STELLAR_ASSET -> stellarAssetService.createStellarAsset(assetId, network, ownerAddress);
                default -> throw new UnsupportedOperationException("Stellar does not support token standard: " + standard);
            };
        }

        // EVM chains (Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism, Fhenix, Inco)
        return switch (standard) {
            case ERC20 -> erc20DeploymentService.deploy(assetId, descriptor, ownerAddress);
            case ERC721 -> erc721DeploymentService.deploy(assetId, descriptor, ownerAddress);
            case ERC1155 -> erc1155DeploymentService.deploy(assetId, descriptor, ownerAddress);
            case ERC3525 -> erc3525DeploymentService.deploy(assetId, descriptor, ownerAddress);
            case ERC4626 -> erc4626DeploymentService.deploy(assetId, descriptor, ownerAddress);
            case ERC7540 -> erc7540DeploymentService.deploy(assetId, descriptor, ownerAddress);
            case CONF_ERC20 -> confidentialErc20Service.deploy(assetId, descriptor, ownerAddress);
            default -> {
                org.slf4j.LoggerFactory.getLogger(TokenDeploymentPortImpl.class)
                        .warn("No EVM deployment service for token standard: {}", standard);
                yield CompletableFuture.completedFuture("UNSUPPORTED_" + standard);
            }
        };
    }
}
