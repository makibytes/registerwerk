package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.api.LendingMarketRegistrar;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Connects the ordinary relational demo fixtures to the disposable Anvil contracts deployed by
 * {@code DeployLocalLendingDemo}. It intentionally does no deployment itself: startup remains
 * deterministic and the same service code still verifies every market from-chain before saving.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
public class LocalLendingDemoSeeder implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LocalLendingDemoSeeder.class);
    private static final String DEMO_CHAIN = "ETHEREUM_SEPOLIA";
    private static final Map<String, String> COMPANY_WALLETS = Map.of(
            "DEMO-NI-001", "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
            "DEMO-RK-001", "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc",
            "DEMO-AF-001", "0x90f79bf6eb2c4f870365e785982e1f101e93b906",
            "DEMO-FD-001", "0x15d34aaf54267db7d7c367839aaf71a00a2c6a65",
            "DEMO-WI-001", "0x9965507d1a55bcc2695c58ba16fb37d819b0a4dc");

    @Value("${registerwerk.lending.local-demo-addresses-file:}")
    private String addressesFile;

    @Value("${registerwerk.lending.local-demo-backend-rpc-url:http://anvil:8545}")
    private String backendRpcUrl;

    @Value("${registerwerk.lending.local-demo-public-rpc-url:http://localhost:48545}")
    private String publicRpcUrl;

    private final AssetRepository assets;
    private final AssetDeploymentRepository deployments;
    private final AssetHolderRepository holders;
    private final LegalEntityRepository entities;
    private final ChainConfigRepository chains;
    private final RpcNodeRepository rpcNodes;
    private final BlockchainClientRegistry clientRegistry;
    private final OrgRegistrationRepository orgRegistrations;
    private final OrgMemberWalletRepository memberWallets;
    private final LendingMarketRepository markets;
    private final LendingMarketRegistrar marketRegistrar;
    private final ContractAddressConfig contractAddresses;

    public LocalLendingDemoSeeder(
            AssetRepository assets,
            AssetDeploymentRepository deployments,
            AssetHolderRepository holders,
            LegalEntityRepository entities,
            ChainConfigRepository chains,
            RpcNodeRepository rpcNodes,
            BlockchainClientRegistry clientRegistry,
            OrgRegistrationRepository orgRegistrations,
            OrgMemberWalletRepository memberWallets,
            LendingMarketRepository markets,
            LendingMarketRegistrar marketRegistrar,
            ContractAddressConfig contractAddresses) {
        this.assets = assets;
        this.deployments = deployments;
        this.holders = holders;
        this.entities = entities;
        this.chains = chains;
        this.rpcNodes = rpcNodes;
        this.clientRegistry = clientRegistry;
        this.orgRegistrations = orgRegistrations;
        this.memberWallets = memberWallets;
        this.markets = markets;
        this.marketRegistrar = marketRegistrar;
        this.contractAddresses = contractAddresses;
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (addressesFile == null || addressesFile.isBlank()) {
            log.info("Local on-chain lending demo is not configured — skipped");
            return;
        }
        Path file = Path.of(addressesFile);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Local lending demo address file does not exist: " + file);
        }
        Properties addresses = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            addresses.load(input);
        }

        ChainConfig chain = chains.findByIdentifier(DEMO_CHAIN)
                .orElseThrow(() -> new IllegalStateException("Demo chain is missing: " + DEMO_CHAIN));
        configureLocalRpc(chain);
        reconcileInfrastructure(addresses);
        Asset greenBond = requireAsset("DEMO-BOND-MC-001");
        Asset infraNote = requireAsset("DEMO-NOTE-AF-001");
        String greenToken = requireAddress(addresses, "GREEN_BOND_TOKEN");
        String infraToken = requireAddress(addresses, "INFRA_NOTE_TOKEN");
        updateDeployment(greenBond, greenToken);
        updateDeployment(infraNote, infraToken);
        updateDeployment(requireAsset("DEMO-EQ-MC-001"), requireAddress(addresses, "DEMO_ERC3643_TOKEN"));
        updateDeployment(requireAsset("DEMO-NFT-MC-001"), requireAddress(addresses, "DEMO_ERC721_TOKEN"));
        updateDeployment(requireAsset("DEMO-COMM-AF-001"), requireAddress(addresses, "DEMO_ERC1155_TOKEN"));
        updateDeployment(requireAsset("DEMO-SFT-MC-001"), requireAddress(addresses, "DEMO_ERC3525_TOKEN"));
        updateDeployment(requireAsset("DEMO-VAULT-AF-001"), requireAddress(addresses, "DEMO_ERC4626_VAULT"));
        updateDeployment(requireAsset("DEMO-VAULT-AF-002"), requireAddress(addresses, "DEMO_ERC7540_VAULT"));

        updateCompanyWallets(chain);
        updateHoldingWallet(greenBond, "DEMO-NI-001");
        updateHoldingWallet(greenBond, "DEMO-RK-001");
        updateHoldingWallet(greenBond, "DEMO-AF-001");
        updateHoldingWallet(infraNote, "DEMO-RK-001");
        updateHoldingWallet(infraNote, "DEMO-FD-001");
        updateHoldingWallet(infraNote, "DEMO-WI-001");

        registerFreshMarket(chain, greenBond, requireAddress(addresses, "GREEN_BOND_MARKET"));
        registerFreshMarket(chain, infraNote, requireAddress(addresses, "INFRA_NOTE_MARKET"));
        log.info("Local on-chain demo linked: all 7 EVM standards, 2 lending markets, 5 funded companies");
    }

    private void reconcileInfrastructure(Properties addresses) {
        String key = DEMO_CHAIN.toLowerCase().replace('_', '-');
        contractAddresses.getAssetTokenFactory().put(key, requireAddress(addresses, "ASSET_TOKEN_FACTORY"));
        contractAddresses.getTrexFactory().put(key, requireAddress(addresses, "TREX_FACTORY"));
        contractAddresses.getIdFactory().put(key, requireAddress(addresses, "ID_FACTORY"));
        contractAddresses.getOrgRegistry().put(key, requireAddress(addresses, "ORG_REGISTRY"));
        contractAddresses.getPermissionRegistry().put(key, requireAddress(addresses, "PERMISSION_REGISTRY"));
        contractAddresses.getPermissionOracle().put(key, requireAddress(addresses, "PERMISSION_ORACLE"));
        contractAddresses.getDappRegistry().put(key, requireAddress(addresses, "DAPP_REGISTRY"));
        contractAddresses.getEcosystemTir().put(key, requireAddress(addresses, "ECOSYSTEM_TIR"));
    }

    private void configureLocalRpc(ChainConfig chain) {
        if (backendRpcUrl == null || !backendRpcUrl.matches("https?://\\S+")) {
            throw new IllegalStateException("Invalid local demo backend RPC URL: " + backendRpcUrl);
        }
        if (publicRpcUrl == null || !publicRpcUrl.matches("https?://\\S+")) {
            throw new IllegalStateException("Invalid local demo public RPC URL: " + publicRpcUrl);
        }

        // chain_config is returned to browsers, so it must contain a host-reachable URL. The
        // backend uses an exclusive node with the Compose-internal hostname instead.
        chain.setRpcUrl(publicRpcUrl);
        chains.save(chain);

        var existingNodes = rpcNodes.findByChainConfig_Identifier(DEMO_CHAIN);
        existingNodes.forEach(node -> node.setExclusive(false));
        RpcNode localNode = existingNodes.stream()
                .filter(node -> "Local Anvil".equals(node.getLabel()))
                .findFirst()
                .orElseGet(RpcNode::new);
        localNode.setChainConfig(chain);
        localNode.setUrl(backendRpcUrl);
        localNode.setLabel("Local Anvil");
        localNode.setEnabled(true);
        localNode.setExclusive(true);
        // Also pin the chaincache-kind node (DemoDataSeeder.syncChaincacheDemoNode, which runs
        // before this — DemoDataSeeder.getOrder()=0 < this class's 20) exclusive alongside anvil,
        // not instead of it: BlockchainClientRegistry.selectBestNodeId ties on lag and prefers
        // CHAINCACHE, so with both pinned chaincache actually receives routed traffic while anvil
        // remains a real, live fallback (stopping the chaincache container demonstrably fails over
        // to anvil) — a single exclusive chaincache node would have no fallback at all.
        existingNodes.stream()
                .filter(node -> node.getKind() == RpcNode.NodeKind.CHAINCACHE)
                .forEach(node -> node.setExclusive(true));
        rpcNodes.saveAll(existingNodes);
        rpcNodes.save(localNode);

        // ApplicationRunner executes before the scheduled health check. Refresh synchronously so
        // the market verifier cannot race startup and accidentally use a public Sepolia endpoint.
        clientRegistry.refreshFromNodes(rpcNodes.findAllWithChainConfig());
    }

    private Asset requireAsset(String assetNumber) {
        return assets.findByAssetNumber(assetNumber)
                .orElseThrow(() -> new IllegalStateException("Demo asset is missing: " + assetNumber));
    }

    private String requireAddress(Properties addresses, String key) {
        String value = addresses.getProperty(key);
        if (value == null || !value.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new IllegalStateException("Invalid or missing " + key + " in " + addressesFile);
        }
        return value.toLowerCase();
    }

    private void updateDeployment(Asset asset, String contractAddress) {
        AssetDeployment deployment = deployments.findByAssetId(asset.getId()).stream()
                .filter(candidate -> candidate.getChain() == de.makibytes.registerwerk.chain.api.Chain.ETHEREUM
                        && candidate.getNetwork() == de.makibytes.registerwerk.chain.api.Network.TESTNET)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Ethereum testnet deployment missing for "
                        + asset.getAssetNumber()));
        deployment.setContractAddress(contractAddress);
        deployment.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        deployments.save(deployment);
    }

    private void updateCompanyWallets(ChainConfig chain) {
        COMPANY_WALLETS.forEach((entityNumber, address) -> {
            LegalEntity entity = entities.findByEntityNumber(entityNumber)
                    .orElseThrow(() -> new IllegalStateException("Demo entity is missing: " + entityNumber));
            var org = orgRegistrations.findByLegalEntityIdAndChainConfigId(entity.getId(), chain.getId())
                    .orElseThrow(() -> new IllegalStateException("Demo org is missing: " + entityNumber));
            var wallets = memberWallets.findByOrgRegistrationIdOrderByCreatedAtDesc(org.getId());
            if (wallets.isEmpty()) {
                throw new IllegalStateException("Demo member wallet is missing: " + entityNumber);
            }
            wallets.forEach(wallet -> wallet.setWalletAddress(address));
            memberWallets.saveAll(wallets);
        });
    }

    private void updateHoldingWallet(Asset asset, String entityNumber) {
        LegalEntity entity = entities.findByEntityNumber(entityNumber)
                .orElseThrow(() -> new IllegalStateException("Demo entity is missing: " + entityNumber));
        var holding = holders.findActiveByInvestorIdAndAssetId(entity.getId(), asset.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Demo holding is missing: " + entityNumber + "/" + asset.getAssetNumber()));
        holding.setWalletAddress(COMPANY_WALLETS.get(entityNumber));
        holders.save(holding);
    }

    private void registerFreshMarket(ChainConfig chain, Asset asset, String marketAddress) {
        markets.findAll().stream()
                .filter(existing -> asset.getId().equals(existing.getCollateralAssetId()))
                .filter(existing -> !existing.getMarketAddress().equalsIgnoreCase(marketAddress))
                .forEach(existing -> existing.setStatus(LendingMarketStatus.RETIRED));
        if (!markets.existsByChainConfigIdAndMarketAddressIgnoreCase(chain.getId(), marketAddress)) {
            marketRegistrar.registerVerifiedMarket(
                    chain.getId(), marketAddress, null, asset.getId(), "aueur", null, "DEMO_SEED");
        }
    }
}
