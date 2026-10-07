package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.ContractAddressConfig;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.lending.api.LendingMarketRegistrar;
import de.makibytes.registerwerk.lending.api.LendingMarketRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.TransactionDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 1 docker gate: an OPTIONAL demo seeder must never take the app down. The seeder used to run
 * under one outer {@code @Transactional} and swallow the market-registration failure, but the
 * (REQUIRED, joined) registrar had already marked the shared transaction rollback-only, so the
 * outer commit threw {@code UnexpectedRollbackException} and the backend crash-looped
 * ("Application run failed"). Runs the seeder through real transaction proxies.
 */
@DisplayName("LocalLendingDemoSeeder: a failing step never fails startup")
class LocalLendingDemoSeederResilienceTest {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void close() {
        if (ctx != null) ctx.close();
    }

    @Test
    @DisplayName("market registration failing inside the (joined) registrar leaves run() completing normally")
    void marketRegistrationFailureDoesNotFailRun(@TempDir Path dir) throws Exception {
        Path addresses = dir.resolve("addresses.env");
        Files.writeString(addresses, addressFile());

        UUID chainId = UUID.randomUUID();
        ChainConfig chain = mock(ChainConfig.class);
        when(chain.getId()).thenReturn(chainId);
        ChainConfigRepository chains = mock(ChainConfigRepository.class);
        when(chains.findByIdentifier("ETHEREUM_SEPOLIA")).thenReturn(Optional.of(chain));

        Asset asset = mock(Asset.class);
        when(asset.getId()).thenReturn(UUID.randomUUID());
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findByAssetNumber(anyString())).thenReturn(Optional.of(asset));

        AssetDeployment deployment = mock(AssetDeployment.class);
        when(deployment.getChain()).thenReturn(Chain.ETHEREUM);
        when(deployment.getNetwork()).thenReturn(Network.TESTNET);
        AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
        when(deployments.findByAssetId(any())).thenReturn(List.of(deployment));

        LegalEntity entity = mock(LegalEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        LegalEntityRepository entities = mock(LegalEntityRepository.class);
        when(entities.findByEntityNumber(anyString())).thenReturn(Optional.of(entity));

        OrgRegistration org = mock(OrgRegistration.class);
        when(org.getId()).thenReturn(UUID.randomUUID());
        OrgRegistrationRepository orgs = mock(OrgRegistrationRepository.class);
        when(orgs.findByLegalEntityIdAndChainConfigId(any(), any())).thenReturn(Optional.of(org));
        OrgMemberWalletRepository wallets = mock(OrgMemberWalletRepository.class);
        when(wallets.findByOrgRegistrationIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(mock(OrgMemberWallet.class)));

        AssetHolderRepository holders = mock(AssetHolderRepository.class);
        when(holders.findActiveByInvestorIdAndAssetId(any(), any())).thenReturn(Optional.of(mock(AssetHolder.class)));
        TokenTransferRepository transfers = mock(TokenTransferRepository.class);
        when(transfers.findByAssetIdOrderByOccurredAtDesc(any(), any())).thenReturn(new PageImpl<>(List.of()));

        RecordingTxManager txm = new RecordingTxManager();
        ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "registerwerk.seed-demo-data", "true",
                "registerwerk.lending.local-demo-addresses-file", addresses.toString(),
                "registerwerk.lending.local-demo-backend-rpc-url", "http://anvil:8545",
                "registerwerk.lending.local-demo-public-rpc-url", "http://localhost:48545")));
        ctx.register(TxConfig.class);
        ctx.registerBean(PlatformTransactionManager.class, () -> txm);
        ctx.registerBean(LendingMarketRegistrar.class, FailingRegistrar::new);
        // The registrar handed to the seeder is the transactional proxy, exactly like LendingMarketService.
        ctx.registerBean(LocalLendingDemoSeeder.class, () -> newSeeder(txm, assets, deployments, holders, entities,
                chains, mock(RpcNodeRepository.class), orgs, wallets, transfers,
                ctx.getBean(LendingMarketRegistrar.class)));
        ctx.refresh();

        org.springframework.boot.ApplicationRunner proxied = ctx.getBean(org.springframework.boot.ApplicationRunner.class);
        assertThatCode(() -> proxied.run(new DefaultApplicationArguments()))
                .as("the seeder must swallow the failure AND not leave a rollback-only outer transaction behind")
                .doesNotThrowAnyException();

        // The independent steps (deployment links, wallets) were still committed.
        verify(deployments, atLeastOnce()).save(any());
        assertThat(txm.commits).as("independent steps commit").isGreaterThan(0);
        assertThat(txm.rollbacks).as("the failed market steps roll back on their own").isGreaterThan(0);
    }

    private static LocalLendingDemoSeeder newSeeder(PlatformTransactionManager txm, AssetRepository assets,
            AssetDeploymentRepository deployments, AssetHolderRepository holders, LegalEntityRepository entities,
            ChainConfigRepository chains, RpcNodeRepository rpcNodes, OrgRegistrationRepository orgs,
            OrgMemberWalletRepository wallets, TokenTransferRepository transfers,
            LendingMarketRegistrar registrar) {
        return new LocalLendingDemoSeeder(assets, deployments, holders, entities, chains, rpcNodes,
                mock(BlockchainClientRegistry.class), orgs, wallets, mock(LendingMarketRepository.class),
                registrar, new ContractAddressConfig(), transfers,
                mock(PaymentRailRepository.class), mock(PaymentRailChainAddressRepository.class), txm);
    }

    private static String addressFile() {
        List<String> keys = new ArrayList<>(List.of("ASSET_TOKEN_FACTORY", "TREX_FACTORY", "ID_FACTORY",
                "ORG_REGISTRY", "PERMISSION_REGISTRY", "PERMISSION_ORACLE", "DAPP_REGISTRY", "ECOSYSTEM_TIR",
                "GREEN_BOND_TOKEN", "INFRA_NOTE_TOKEN", "DEMO_ERC3643_TOKEN", "DEMO_ERC721_TOKEN",
                "DEMO_ERC1155_TOKEN", "DEMO_ERC3525_TOKEN", "DEMO_ERC4626_VAULT", "DEMO_ERC7540_VAULT",
                "LOAN_TOKEN", "GREEN_BOND_MARKET", "INFRA_NOTE_MARKET"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            sb.append(keys.get(i)).append("=0x").append(String.format("%040x", i + 1)).append('\n');
        }
        return sb.toString();
    }

    @EnableTransactionManagement(proxyTargetClass = true)
    static class TxConfig {
    }

    /** Stands in for LendingMarketService.registerVerifiedMarket: REQUIRED (joins), throws like the real one. */
    static class FailingRegistrar implements LendingMarketRegistrar {
        @Override
        @Transactional
        public void registerVerifiedMarket(UUID chainConfigId, String marketAddress, String vaultAddress,
                                           UUID collateralAssetId, String loanRailCode, UUID registeredBy,
                                           String registeredByRole) {
            throw new IllegalStateException("Chain ETHEREUM_SEPOLIA: all configured RPC nodes are quarantined "
                    + "(chain id / genesis mismatch or implausible height)");
        }
    }

    /** Minimal real transaction semantics: joining, REQUIRES_NEW suspension, rollback-only marking. */
    static final class RecordingTxManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<State> current = new ThreadLocal<>();
        int commits;
        int rollbacks;

        static final class State {
            boolean rollbackOnly;
        }

        /** Reads the live per-thread state, like DataSourceTransactionObject reads its connection holder. */
        final class Tx implements SmartTransactionObject {
            @Override public boolean isRollbackOnly() { State s = current.get(); return s != null && s.rollbackOnly; }
            @Override public void flush() { }
        }

        @Override protected Object doGetTransaction() { return new Tx(); }
        @Override protected boolean isExistingTransaction(Object tx) { return current.get() != null; }
        @Override protected void doBegin(Object tx, TransactionDefinition def) { current.set(new State()); }
        @Override protected Object doSuspend(Object tx) { State s = current.get(); current.remove(); return s; }
        @Override protected void doResume(Object tx, Object suspended) { current.set((State) suspended); }
        @Override protected void doCommit(DefaultTransactionStatus status) { current.remove(); commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { current.remove(); rollbacks++; }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            current.get().rollbackOnly = true;
        }
    }
}
