package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import org.junit.jupiter.api.Test;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.protocol.Web3j;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnchainHandoverVerifierTest {

    private final EvmContractService evm = mock(EvmContractService.class);
    private final Web3j web3j = mock(Web3j.class);
    private final OnchainHandoverVerifier verifier = new OnchainHandoverVerifier(evm);
    private final String controller = "0x" + "ab".repeat(20);

    private AssetDeployment deployment() {
        AssetDeployment d = new AssetDeployment();
        d.setId(UUID.randomUUID());
        d.setChain(Chain.ETHEREUM);
        d.setChainConfigId(UUID.randomUUID());
        d.setContractAddress("0x" + "cd".repeat(20));
        when(evm.evmClient(d.getChainConfigId())).thenReturn(web3j);
        return d;
    }

    @Test
    void evmFamilyIsDistinguishedFromOtherChains() {
        assertThat(OnchainHandoverVerifier.isEvm(Chain.ETHEREUM)).isTrue();
        assertThat(OnchainHandoverVerifier.isEvm(Chain.BASE)).isTrue();
        assertThat(OnchainHandoverVerifier.isEvm(Chain.SOLANA)).isFalse();
        assertThat(OnchainHandoverVerifier.isEvm(Chain.CANTON)).isFalse();
        assertThat(OnchainHandoverVerifier.isEvm(Chain.STARKNET)).isFalse();
        assertThat(OnchainHandoverVerifier.isEvm(Chain.STELLAR)).isFalse();
        assertThat(OnchainHandoverVerifier.isEvm(null)).isFalse();
    }

    @Test
    void readsRegistryFirst() {
        AssetDeployment d = deployment();
        when(evm.call(eq(web3j), eq(d.getContractAddress()), any(Function.class)))
                .thenReturn(List.of(new Address(controller)));

        var obs = verifier.observeController(d);

        assertThat(obs.function()).isEqualTo("registry");
        assertThat(obs.observed()).isEqualToIgnoringCase(controller);
    }

    @Test
    void fallsBackToOwnerWhenRegistryReverts() {
        AssetDeployment d = deployment();
        when(evm.call(eq(web3j), eq(d.getContractAddress()), any(Function.class)))
                .thenThrow(new RuntimeException("execution reverted"))
                .thenReturn(List.of(new Address(controller)));

        var obs = verifier.observeController(d);

        assertThat(obs.function()).isEqualTo("owner");
    }

    @Test
    void failsClosedWhenNeitherGetterCanBeRead() {
        AssetDeployment d = deployment();
        when(evm.call(eq(web3j), eq(d.getContractAddress()), any(Function.class)))
                .thenThrow(new RuntimeException("rpc down"));

        assertThatThrownBy(() -> verifier.observeController(d))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not verified");
    }

    @Test
    void failsClosedOnAnEmptyAnswerAndOnAMissingChainConfig() {
        AssetDeployment d = deployment();
        when(evm.call(eq(web3j), eq(d.getContractAddress()), any(Function.class))).thenReturn(List.of());
        assertThatThrownBy(() -> verifier.observeController(d)).isInstanceOf(IllegalStateException.class);

        AssetDeployment noConfig = new AssetDeployment();
        noConfig.setId(UUID.randomUUID());
        assertThatThrownBy(() -> verifier.observeController(noConfig))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no chain configuration");
    }
}
