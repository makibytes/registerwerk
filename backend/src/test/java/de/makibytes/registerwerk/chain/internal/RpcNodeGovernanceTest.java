package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.ChaincacheStreamStatus;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeActor;
import de.makibytes.registerwerk.chain.api.RpcNodeChainVerifier;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.chain.events.RpcNodeChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RpcNodeService governance: https-only, chain identity on add, audit evidence (P4C-1 / P4C-6)")
class RpcNodeGovernanceTest {

    @Mock RpcNodeRepository rpcNodeRepository;
    @Mock ChainConfigRepository chainConfigRepository;
    @Mock ApplicationEventPublisher events;
    @Mock ChaincacheClient chaincacheClient;
    @Mock ObjectProvider<RpcNodeChainVerifier> verifierProvider;
    @Mock RpcNodeChainVerifier verifier;

    private final UUID chainId = UUID.randomUUID();
    private final ChaincacheStreamStatus streamStatus = id -> false;
    private final ChaincacheCredentials credentials = url -> Optional.empty();
    private ChainConfig chain;

    private RpcNodeService service(boolean allowInsecurePrivate) {
        return new RpcNodeService(rpcNodeRepository, chainConfigRepository, events, chaincacheClient,
                new ObjectMapper(), streamStatus, credentials, new RpcNodeUrlPolicy(allowInsecurePrivate),
                verifierProvider);
    }

    @BeforeEach
    void setUp() {
        chain = new ChainConfig();
        chain.setId(chainId);
        chain.setIdentifier("ETHEREUM_MAINNET");
        chain.setChainType(ChainConfig.ChainType.EVM);
        chain.setChainId(1L);
        org.mockito.Mockito.lenient().when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain));
    }

    @Test
    @DisplayName("plain http to a public host is refused")
    void httpPublicRefused() {
        assertThatThrownBy(() -> service(true).addNode(chainId, "http://rpc.example.org", "x", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
        verify(rpcNodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("plain http to a private host needs registerwerk.rpc.allow-insecure-private")
    void httpPrivateNeedsFlag() {
        assertThatThrownBy(() -> service(false).addNode(chainId, "http://anvil:8545", "x", null))
                .isInstanceOf(IllegalArgumentException.class);
        when(chaincacheClient.detect("http://anvil:8545")).thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(i -> i.getArgument(0));
        assertThat(service(true).addNode(chainId, "http://anvil:8545", "x", null).getUrl()).isEqualTo("http://anvil:8545");
    }

    @Test
    @DisplayName("host allow-list of the chain is enforced")
    void allowListEnforced() {
        chain.setRpcAllowedHosts("*.infura.io, node.internal.example");
        assertThatThrownBy(() -> service(false).addNode(chainId, "https://evil.example/rpc", "x", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowed hosts");
    }

    @Test
    @DisplayName("a node answering another chain id is refused on add and never saved")
    void chainMismatchRefusedOnAdd() {
        when(verifierProvider.getIfAvailable()).thenReturn(verifier);
        when(verifier.verify(chain, "https://polygon.example/rpc")).thenReturn(
                new RpcNodeChainVerifier.Verdict(RpcNodeChainVerifier.Outcome.MISMATCH, "eth_chainId 137 differs"));

        assertThatThrownBy(() -> service(false).addNode(chainId, "https://polygon.example/rpc", "x", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eth_chainId 137");
        verify(rpcNodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("update audit event carries redacted old and new URL, actor and second approver")
    void updateEventCarriesEvidence() {
        UUID nodeId = UUID.randomUUID();
        RpcNode node = new RpcNode();
        node.setChainConfig(chain);
        node.setUrl("https://old.example/v3/0123456789abcdef0123456789abcdef?apikey=SECRET");
        node.setHealthy(true);
        when(rpcNodeRepository.findByIdAndChainConfig_Id(nodeId, chainId)).thenReturn(Optional.of(node));
        when(chaincacheClient.detect("https://new.example/rpc")).thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(i -> i.getArgument(0));
        UUID actor = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        service(false).updateNode(chainId, nodeId, "https://new.example/rpc", "n",
                new RpcNodeActor(actor, "REGISTRY_ADMIN", approver, UUID.randomUUID()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        RpcNodeChangedEvent event = (RpcNodeChangedEvent) captor.getValue();
        assertThat(event.actorId()).isEqualTo(actor);
        assertThat(event.dualControlApproverId()).isEqualTo(approver);
        assertThat(event.payload().get("url")).isEqualTo("https://new.example/rpc");
        assertThat(event.payload().get("oldUrl").toString())
                .contains("old.example").doesNotContain("SECRET").doesNotContain("0123456789abcdef");
        assertThat(node.isHealthy()).as("a changed endpoint must prove itself again").isFalse();
    }
}
