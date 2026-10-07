package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.Web3j;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H5: the nightly read-back never turns "could not read" into "not frozen". */
@DisplayName("OnChainFrozenReader (H5)")
class OnChainFrozenReaderTest {

    private static final String WALLET = "0x" + "aa".repeat(20);
    private static final String CONTRACT = "0x" + "cc".repeat(20);

    private final EvmContractService evm = mock(EvmContractService.class);
    private final Erc3643SuiteRepository suites = mock(Erc3643SuiteRepository.class);
    private final Web3j web3j = mock(Web3j.class);
    private OnChainFrozenReader reader;

    @BeforeEach
    void setUp() {
        reader = new OnChainFrozenReader(evm, suites);
    }

    private AssetDeployment deployment(Chain chain) {
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setChain(chain);
        d.setContractAddress(CONTRACT);
        d.setChainConfigId(UUID.randomUUID());
        when(evm.evmClient(d.getChainConfigId())).thenReturn(web3j);
        return d;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void givenFrozen(String contract, boolean frozen) {
        List<Type> out = List.of(new Bool(frozen));
        when(evm.call(eq(web3j), eq(contract), any(Function.class))).thenReturn(out);
    }

    @Test
    @DisplayName("reads isFrozen(address) from the deployment's token")
    void readsFromTheDeploymentToken() {
        AssetDeployment dep = deployment(Chain.ETHEREUM);
        givenFrozen(CONTRACT, true);

        assertThat(reader.isFrozen(dep, WALLET)).contains(true);

        ArgumentCaptor<Function> fn = ArgumentCaptor.forClass(Function.class);
        verify(evm).call(eq(web3j), eq(CONTRACT), fn.capture());
        assertThat(fn.getValue().getName()).isEqualTo("isFrozen");
    }

    @Test
    @DisplayName("an ERC-3643 suite is read at its token address, not at the deployment record's")
    void readsFromTheSuiteToken() {
        AssetDeployment dep = deployment(Chain.ETHEREUM);
        String token = "0x" + "dd".repeat(20);
        Erc3643Suite suite = new Erc3643Suite();
        suite.setTokenAddress(token);
        when(suites.findByAssetDeploymentId(dep.getId())).thenReturn(Optional.of(suite));
        givenFrozen(token, false);

        assertThat(reader.isFrozen(dep, WALLET)).contains(false);
    }

    @Test
    @DisplayName("non-EVM chains, a missing chain config and read errors are unknown, never 'not frozen'")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unreadableIsUnknown() {
        assertThat(reader.isFrozen(deployment(Chain.SOLANA), WALLET)).isEmpty();
        AssetDeployment noConfig = new AssetDeployment();
        noConfig.setId(UUID.randomUUID());
        noConfig.setChain(Chain.ETHEREUM);
        assertThat(reader.isFrozen(noConfig, WALLET)).isEmpty();

        AssetDeployment failing = deployment(Chain.ETHEREUM);
        when(evm.call(eq(web3j), eq(CONTRACT), any(Function.class))).thenThrow(new RuntimeException("rpc down"));
        assertThat(reader.isFrozen(failing, WALLET)).isEmpty();

        AssetDeployment empty = deployment(Chain.ETHEREUM);
        when(evm.call(eq(web3j), eq(CONTRACT), any(Function.class))).thenReturn((List) List.of());
        assertThat(reader.isFrozen(empty, WALLET)).isEmpty();

        AssetDeployment noAddress = deployment(Chain.ETHEREUM);
        noAddress.setContractAddress(null);
        assertThat(reader.isFrozen(noAddress, WALLET)).isEmpty();
        verify(evm, never()).call(eq(web3j), eq((String) null), any(Function.class));
    }
}
