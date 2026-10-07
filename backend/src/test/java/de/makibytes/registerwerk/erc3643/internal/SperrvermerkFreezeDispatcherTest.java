package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.Erc3525AdminPort;
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.internal.SperrvermerkFreezeDispatcher.Path;
import de.makibytes.registerwerk.erc3643.internal.SperrvermerkFreezeDispatcher.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H5: which standard/chain is frozen through which admin path, and which ones are explicitly UNSUPPORTED. */
@DisplayName("SperrvermerkFreezeDispatcher (H5)")
class SperrvermerkFreezeDispatcherTest {

    private static final String WALLET = "0x" + "aa".repeat(20);
    private static final String REASON = "eWpG §16 Sperrvermerk: Court order";

    private final Erc3643SuiteRepository suites = mock(Erc3643SuiteRepository.class);
    private final AssetLookupPort assets = mock(AssetLookupPort.class);
    private final Erc3643LifecycleService erc3643 = mock(Erc3643LifecycleService.class);
    private final TokenAdminPort tokenAdmin = mock(TokenAdminPort.class);
    private final Erc3525AdminPort erc3525 = mock(Erc3525AdminPort.class);
    private SperrvermerkFreezeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new SperrvermerkFreezeDispatcher(suites, assets, erc3643, tokenAdmin, erc3525);
    }

    private AssetDeployment deployment(Chain chain, TokenStandard standard) {
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setAssetId(UUID.randomUUID());
        d.setChain(chain);
        d.setContractAddress("0x" + "cc".repeat(20));
        d.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        when(assets.findById(d.getAssetId())).thenReturn(Optional.of(new AssetLookupPort.AssetInfo(
                d.getAssetId(), "Asset", null, standard, null, null, UUID.randomUUID(), "A-1", "ISSUED")));
        return d;
    }

    @ParameterizedTest(name = "{0} is frozen through the token admin port")
    @EnumSource(value = TokenStandard.class, names = {"ERC20", "ERC721", "ERC1155", "ERC4626", "ERC7540", "CONF_ERC3643"})
    void tokenAdminStandards(TokenStandard standard) {
        AssetDeployment dep = deployment(Chain.ETHEREUM, standard);
        UUID tx = UUID.randomUUID();
        when(tokenAdmin.freezeAddress(eq(dep.getId()), eq(WALLET), eq(REASON), eq(REASON), any(), eq("SYSTEM"))).thenReturn(tx);
        when(tokenAdmin.unfreezeAfterBlockLift(dep.getId(), WALLET)).thenReturn(tx);

        Route route = dispatcher.route(dep);

        assertThat(route.path()).isEqualTo(Path.TOKEN_ADMIN);
        assertThat(dispatcher.freeze(route, dep, WALLET, REASON)).isEqualTo(tx);
        assertThat(dispatcher.release(route, dep, WALLET)).isEqualTo(tx);
    }

    @Test
    @DisplayName("ERC-3525 is frozen and released through the ERC-3525 admin port")
    void erc3525() {
        AssetDeployment dep = deployment(Chain.POLYGON, TokenStandard.ERC3525);
        UUID tx = UUID.randomUUID();
        when(erc3525.freezeAddress(eq(dep.getId()), eq(WALLET), eq(REASON), any(), eq("SYSTEM"))).thenReturn(tx);
        when(erc3525.unfreezeAfterBlockLift(dep.getId(), WALLET)).thenReturn(tx);

        Route route = dispatcher.route(dep);

        assertThat(route.path()).isEqualTo(Path.ERC3525_ADMIN);
        assertThat(dispatcher.freeze(route, dep, WALLET, REASON)).isEqualTo(tx);
        assertThat(dispatcher.release(route, dep, WALLET)).isEqualTo(tx);
        verify(tokenAdmin, never()).freezeAddress(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("a deployment with an ERC-3643 suite goes through Erc3643LifecycleService, whatever its standard says")
    void erc3643Suite() {
        AssetDeployment dep = deployment(Chain.ETHEREUM, TokenStandard.ERC3643);
        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(UUID.randomUUID());
        when(suites.findByAssetDeploymentId(dep.getId())).thenReturn(Optional.of(suite));
        UUID tx = UUID.randomUUID();
        when(erc3643.freezeAddress(eq(suite.getId()), eq(WALLET), any(), eq("SYSTEM"))).thenReturn(tx);
        when(erc3643.unfreezeAddressForBlockLift(suite.getId(), WALLET)).thenReturn(tx);

        Route route = dispatcher.route(dep);

        assertThat(route.path()).isEqualTo(Path.SUITE);
        assertThat(route.suiteId()).isEqualTo(suite.getId());
        assertThat(dispatcher.freeze(route, dep, WALLET, REASON)).isEqualTo(tx);
        assertThat(dispatcher.release(route, dep, WALLET)).isEqualTo(tx);
    }

    @Test
    @DisplayName("a plain ERC-3643 deployment WITHOUT a suite row is not UNSUPPORTED: the token admin port fails it loudly")
    void erc3643WithoutSuiteIsAFailureNotAGap() {
        AssetDeployment dep = deployment(Chain.ETHEREUM, TokenStandard.ERC3643);

        assertThat(dispatcher.route(dep).path()).isEqualTo(Path.TOKEN_ADMIN);
    }

    @Test
    @DisplayName("an unknown asset falls back to the token admin port (the failure is recorded, never assumed away)")
    void unknownAssetFallsBack() {
        AssetDeployment dep = new AssetDeployment();
        dep.setId(UUID.randomUUID());
        dep.setAssetId(UUID.randomUUID());
        dep.setChain(Chain.ETHEREUM);
        when(assets.findById(dep.getAssetId())).thenReturn(Optional.empty());

        assertThat(dispatcher.route(dep).path()).isEqualTo(Path.TOKEN_ADMIN);
    }

    @ParameterizedTest(name = "{0} deployments are UNSUPPORTED with a reason the operator can act on")
    @EnumSource(value = Chain.class, names = {"SOLANA", "STARKNET", "STELLAR", "CANTON"})
    void nonEvmChainsAreUnsupportedWithAReason(Chain chain) {
        AssetDeployment dep = deployment(chain, TokenStandard.ERC20);

        Route route = dispatcher.route(dep);

        assertThat(route.path()).isEqualTo(Path.UNSUPPORTED);
        assertThat(route.supported()).isFalse();
        assertThat(route.unsupportedReason()).contains("operator").isNotBlank();
        assertThatThrownBy(() -> dispatcher.freeze(route, dep, WALLET, REASON)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dispatcher.release(route, dep, WALLET)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0} has no automated freeze")
    @EnumSource(value = TokenStandard.class, names = {"CONF_ERC20", "SPL", "SPL_2022", "SPL_2022_BOND", "SPL_2022_CONFIDENTIAL",
            "STARKNET_ERC20", "STARKNET_ERC3525", "STELLAR_ASSET", "CANTON_TOKEN", "DAML_BOND_FIXED", "DAML_BOND_FLOATING",
            "DAML_BOND_ZERO"})
    void standardsWithoutAFreezeAreUnsupported(TokenStandard standard) {
        AssetDeployment dep = deployment(Chain.ETHEREUM, standard);

        Route route = dispatcher.route(dep);

        assertThat(route.path()).isEqualTo(Path.UNSUPPORTED);
        assertThat(route.unsupportedReason()).isNotBlank();
    }

    @Test
    @DisplayName("every standard maps to exactly one path - a new TokenStandard cannot be silently skipped")
    void everyStandardHasARoute() {
        for (TokenStandard standard : EnumSet.allOf(TokenStandard.class)) {
            AssetDeployment dep = deployment(Chain.ETHEREUM, standard);
            assertThat(dispatcher.route(dep).path()).as(standard.name()).isNotNull();
        }
    }

    @Test
    @DisplayName("isLive: only a CONFIRMED deployment with a real contract address has a token to freeze")
    void isLive() {
        AssetDeployment dep = deployment(Chain.ETHEREUM, TokenStandard.ERC20);
        assertThat(SperrvermerkFreezeDispatcher.isLive(dep)).isTrue();
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.PENDING);
        assertThat(SperrvermerkFreezeDispatcher.isLive(dep)).isFalse();
        dep.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        dep.setContractAddress(null);
        assertThat(SperrvermerkFreezeDispatcher.isLive(dep)).isFalse();
        dep.setContractAddress("0x-PENDING-123");
        assertThat(SperrvermerkFreezeDispatcher.isLive(dep)).isFalse();
        ReflectionTestUtils.setField(dep, "deploymentStatus", AssetDeployment.DeploymentStatus.FAILED);
        dep.setContractAddress("0x" + "cc".repeat(20));
        assertThat(SperrvermerkFreezeDispatcher.isLive(dep)).isFalse();
    }
}
