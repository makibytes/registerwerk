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
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketStatus;
import de.makibytes.registerwerk.lending.api.LendingMarketRegistrar;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddress;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
public class LocalLendingDemoSeeder implements ApplicationRunner, Ordered, de.makibytes.registerwerk.shared.DemoOnly {

    private static final Logger log = LoggerFactory.getLogger(LocalLendingDemoSeeder.class);
    private static final String DEMO_CHAIN = "ETHEREUM_SEPOLIA";
    private static final Map<String, String> COMPANY_WALLETS = Map.of(
            "DEMO-NI-001", "0x70997970c51812dc3a010c7d01b50e0d17dc79c8",
            "DEMO-RK-001", "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc",
            "DEMO-AF-001", "0x90f79bf6eb2c4f870365e785982e1f101e93b906",
            "DEMO-FD-001", "0x15d34aaf54267db7d7c367839aaf71a00a2c6a65",
            "DEMO-WI-001", "0x9965507d1a55bcc2695c58ba16fb37d819b0a4dc");

    /**
     * The synthetic Green Bond holder wallets {@code DemoDataSeeder} writes into both the register
     * and the indexed transfer history, keyed lower-case, by the demo entity that owns them.
     * {@link #updateHoldingWallet} moves those register rows onto the funded Anvil accounts, so
     * the seeded transfers must follow — otherwise the holder sync finds finalized balances on
     * wallets with no register row and blocks the asset (review phase 2 veto N2).
     */
    static final Map<String, String> SEEDED_GREEN_BOND_WALLETS = Map.of(
            "0x1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b", "DEMO-NI-001",
            "0x2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c", "DEMO-RK-001",
            "0x3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d", "DEMO-AF-001");

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
    private final TokenTransferRepository tokenTransfers;
    private final PaymentRailRepository paymentRails;
    private final PaymentRailChainAddressRepository paymentRailAddresses;
    private final TransactionTemplate stepTx;

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
            ContractAddressConfig contractAddresses,
            TokenTransferRepository tokenTransfers,
            PaymentRailRepository paymentRails,
            PaymentRailChainAddressRepository paymentRailAddresses,
            PlatformTransactionManager transactionManager) {
        // One fresh transaction per seeding step: a step that fails rolls back alone, never poisoning
        // a shared transaction (a joined REQUIRED callee marks it rollback-only even when we catch).
        this.stepTx = new TransactionTemplate(transactionManager);
        this.stepTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.paymentRails = paymentRails;
        this.paymentRailAddresses = paymentRailAddresses;
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
        this.tokenTransfers = tokenTransfers;
    }

    @Override
    public int getOrder() {
        return 20;
    }

    /**
     * Never lets a stale/partial on-chain demo deployment (e.g. an interrupted
     * {@code demo-onchain-deploy} retry, or an anvil that was recreated while the chain identity pin
     * is stale and every RPC node is quarantined) fail the whole application's startup — this is
     * optional local-demo enrichment behind {@code registerwerk.seed-demo-data}, not core
     * functionality. There is deliberately NO outer transaction: each step runs in its own
     * {@code REQUIRES_NEW} transaction with the catch outside it. A joined (REQUIRED) callee such as
     * {@code LendingMarketService.registerVerifiedMarket} marks the shared transaction rollback-only
     * when it throws, so catching inside one outer transaction still ended in
     * {@code UnexpectedRollbackException} at commit and a crash loop. A failed step is logged as one
     * WARN line and skipped; the steps that succeeded stay committed.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (addressesFile == null || addressesFile.isBlank()) {
            log.info("Local on-chain lending demo is not configured — skipped");
            return;
        }
        Properties addresses;
        try {
            addresses = loadAddresses();
        } catch (Exception e) {
            log.warn("Local on-chain lending demo skipped — {}", e.getMessage());
            return;
        }
        List<String> failed = new ArrayList<>();
        step("RPC node + infrastructure addresses", failed, () -> {
            ChainConfig chain = requireChain();
            configureLocalRpc(chain);
            reconcileInfrastructure(addresses);
        });
        step("asset deployments", failed, () -> {
            updateDeployment(requireAsset("DEMO-BOND-MC-001"), requireAddress(addresses, "GREEN_BOND_TOKEN"));
            updateDeployment(requireAsset("DEMO-NOTE-AF-001"), requireAddress(addresses, "INFRA_NOTE_TOKEN"));
            updateDeployment(requireAsset("DEMO-EQ-MC-001"), requireAddress(addresses, "DEMO_ERC3643_TOKEN"));
            updateDeployment(requireAsset("DEMO-NFT-MC-001"), requireAddress(addresses, "DEMO_ERC721_TOKEN"));
            updateDeployment(requireAsset("DEMO-COMM-AF-001"), requireAddress(addresses, "DEMO_ERC1155_TOKEN"));
            updateDeployment(requireAsset("DEMO-SFT-MC-001"), requireAddress(addresses, "DEMO_ERC3525_TOKEN"));
            updateDeployment(requireAsset("DEMO-VAULT-AF-001"), requireAddress(addresses, "DEMO_ERC4626_VAULT"));
            updateDeployment(requireAsset("DEMO-VAULT-AF-002"), requireAddress(addresses, "DEMO_ERC7540_VAULT"));
        });
        step("company wallets and holdings", failed, () -> {
            Asset greenBond = requireAsset("DEMO-BOND-MC-001");
            Asset infraNote = requireAsset("DEMO-NOTE-AF-001");
            updateCompanyWallets(requireChain());
            updateHoldingWallet(greenBond, "DEMO-NI-001");
            updateHoldingWallet(greenBond, "DEMO-RK-001");
            updateHoldingWallet(greenBond, "DEMO-AF-001");
            remapSeededTransferWallets(greenBond);
            updateHoldingWallet(infraNote, "DEMO-RK-001");
            updateHoldingWallet(infraNote, "DEMO-FD-001");
            updateHoldingWallet(infraNote, "DEMO-WI-001");
        });
        step("loan payment rail", failed, () -> bindLoanRail(requireChain(), requireAddress(addresses, "LOAN_TOKEN")));
        step("Green Bond lending market", failed, () -> registerFreshMarket(requireChain(),
                requireAsset("DEMO-BOND-MC-001"), requireAddress(addresses, "GREEN_BOND_MARKET")));
        step("Infra Note lending market", failed, () -> registerFreshMarket(requireChain(),
                requireAsset("DEMO-NOTE-AF-001"), requireAddress(addresses, "INFRA_NOTE_MARKET")));
        if (failed.isEmpty()) {
            log.info("Local on-chain demo linked: all 7 EVM standards, 2 lending markets, 5 funded companies");
        } else {
            log.warn("Local on-chain lending demo only partially linked — skipped: {}. Optional demo "
                    + "enrichment; recreating the local anvil needs its chain identity re-pinned, then "
                    + "rerun after confirming demo-onchain-deploy completed cleanly", failed);
        }
    }

    /** Runs one seeding step in its own transaction; a failure is one WARN line (no stack) and a skip. */
    private void step(String name, List<String> failed, Runnable work) {
        try {
            stepTx.executeWithoutResult(status -> work.run());
        } catch (Exception e) {
            failed.add(name);
            log.warn("Local on-chain lending demo step '{}' skipped: {}", name, e.getMessage());
            log.debug("Local on-chain lending demo step '{}' failure detail", name, e);
        }
    }

    private Properties loadAddresses() throws IOException {
        Path file = Path.of(addressesFile);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Local lending demo address file does not exist: " + file);
        }
        Properties addresses = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            addresses.load(input);
        }
        return addresses;
    }

    private ChainConfig requireChain() {
        return chains.findByIdentifier(DEMO_CHAIN)
                .orElseThrow(() -> new IllegalStateException("Demo chain is missing: " + DEMO_CHAIN));
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
        String repoFactory = addresses.getProperty("REPO_MARKET_FACTORY");
        if (repoFactory != null && !repoFactory.isBlank()) {
            contractAddresses.getRepoMarketFactory().put(key, repoFactory.trim());
        }
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
        if (deployment.getChainConfigId() == null) {
            chains.findByIdentifier(DEMO_CHAIN).ifPresent(c -> deployment.setChainConfigId(c.getId()));
        }
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

    /**
     * Re-points the seeded Green Bond transfer history from the synthetic wallets to the same
     * entities' Anvil wallets (the ones {@link #updateHoldingWallet} put on the register), so the
     * holder sync reconciles instead of blocking. Keyed on the fixed synthetic addresses rather
     * than on the holding's previous wallet, so it also repairs volumes seeded before this fix.
     */
    void remapSeededTransferWallets(Asset asset) {
        int remapped = 0;
        for (TokenTransfer t : tokenTransfers.findByAssetIdOrderByOccurredAtDesc(
                asset.getId(), org.springframework.data.domain.Pageable.unpaged())) {
            String from = companyWalletFor(t.getFromAddress());
            String to = companyWalletFor(t.getToAddress());
            if (from != null) t.setFromAddress(from);
            if (to != null) t.setToAddress(to);
            if (from != null || to != null) {
                tokenTransfers.save(t);
                remapped++;
            }
        }
        if (remapped > 0) {
            log.info("Re-pointed {} seeded transfer(s) of {} onto the demo companies' Anvil wallets",
                    remapped, asset.getAssetNumber());
        }
    }

    private static String companyWalletFor(String seededWallet) {
        if (seededWallet == null) return null;
        String entityNumber = SEEDED_GREEN_BOND_WALLETS.get(seededWallet.toLowerCase(Locale.ROOT));
        return entityNumber == null ? null : COMPANY_WALLETS.get(entityNumber);
    }

    /** Market registration checks the loan token against the rail; point the demo rail at the demo stablecoin. */
    private void bindLoanRail(ChainConfig chain, String loanToken) {
        paymentRails.findByCode("aueur").ifPresent(rail -> {
            var existing = paymentRailAddresses.findByPaymentRailId(rail.getId()).stream()
                    .filter(a -> chain.getId().equals(a.getChainConfigId())).findFirst();
            PaymentRailChainAddress address = existing.orElseGet(() -> {
                PaymentRailChainAddress created = new PaymentRailChainAddress();
                created.setPaymentRailId(rail.getId());
                created.setChainConfigId(chain.getId());
                return created;
            });
            address.setTokenAddress(loanToken);
            paymentRailAddresses.save(address);
        });
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
