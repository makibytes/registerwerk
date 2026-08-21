package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RpcNodeService — kind-aware add/update/refresh, capability probing on CHAINCACHE nodes")
class RpcNodeServiceTest {

    @Mock RpcNodeRepository rpcNodeRepository;
    @Mock ChainConfigRepository chainConfigRepository;
    @Mock ApplicationEventPublisher events;
    @Mock ChaincacheClient chaincacheClient;

    private RpcNodeService service;
    private final UUID chainId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RpcNodeService(rpcNodeRepository, chainConfigRepository, events, chaincacheClient);
    }

    private ChainConfig chain() {
        ChainConfig chain = new ChainConfig();
        chain.setId(chainId);
        chain.setIdentifier("ANVIL_TESTNET");
        return chain;
    }

    @Test
    @DisplayName("addNode with no kind defaults to DIRECT_RPC and never probes chaincache")
    void addNode_defaultsToDirectRpc() {
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));

        RpcNode node = service.addNode(chainId, "http://anvil:8545", "Anvil");

        assertThat(node.getKind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(node.getCapabilities()).isNull();
        verify(chaincacheClient, org.mockito.Mockito.never()).probeCapabilities(any(), any());
    }

    @Test
    @DisplayName("addNode with kind=CHAINCACHE probes and stores capabilities on success")
    void addNode_chaincacheKind_probesAndStoresCapabilities() {
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chaincacheClient.probeCapabilities("http://chaincache:8080", "anvil"))
                .thenReturn(Optional.of(new ChaincacheClient.ChainCapabilitiesProbe(
                        "anvil", "INSTANT", 0L, 0L, List.of("eth", "debug"), true, "AVAILABLE", true, false)));

        RpcNode node = service.addNode(chainId, "http://chaincache:8080/anvil/rpc", "Chaincache",
                RpcNode.NodeKind.CHAINCACHE, "http://chaincache:8080", "anvil");

        assertThat(node.getKind()).isEqualTo(RpcNode.NodeKind.CHAINCACHE);
        assertThat(node.getCapabilities()).containsEntry("finalityModel", "INSTANT");
        assertThat(node.getCapabilities()).containsEntry("durableStreamAvailable", true);
    }

    @Test
    @DisplayName("addNode with kind=CHAINCACHE leaves capabilities null when the probe fails (not rejected)")
    void addNode_chaincacheKind_probeFailureDoesNotRejectAdd() {
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chaincacheClient.probeCapabilities(any(), any())).thenReturn(Optional.empty());

        RpcNode node = service.addNode(chainId, "http://chaincache:8080/anvil/rpc", "Chaincache",
                RpcNode.NodeKind.CHAINCACHE, "http://chaincache:8080", "anvil");

        assertThat(node.getKind()).isEqualTo(RpcNode.NodeKind.CHAINCACHE);
        assertThat(node.getCapabilities()).isNull();
    }

    @Test
    @DisplayName("updateNode re-probes when kind is CHAINCACHE and clears capabilities when switched to DIRECT_RPC")
    void updateNode_clearsCapabilitiesWhenSwitchedToDirectRpc() {
        RpcNode existing = new RpcNode();
        existing.setChainConfig(chain());
        existing.setKind(RpcNode.NodeKind.CHAINCACHE);
        existing.setCapabilities(java.util.Map.of("finalityModel", "INSTANT"));
        UUID nodeId = UUID.randomUUID();
        when(rpcNodeRepository.findByIdAndChainConfig_Id(nodeId, chainId)).thenReturn(Optional.of(existing));
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));

        RpcNode updated = service.updateNode(chainId, nodeId, "http://anvil:8545", "Anvil",
                RpcNode.NodeKind.DIRECT_RPC, null, null);

        assertThat(updated.getKind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(updated.getCapabilities()).isNull();
        verify(chaincacheClient, org.mockito.Mockito.never()).probeCapabilities(any(), any());
    }

    @Test
    @DisplayName("refreshCapabilities is a no-op for a DIRECT_RPC node")
    void refreshCapabilities_noOpForDirectRpc() {
        RpcNode existing = new RpcNode();
        existing.setChainConfig(chain());
        existing.setKind(RpcNode.NodeKind.DIRECT_RPC);
        UUID nodeId = UUID.randomUUID();
        when(rpcNodeRepository.findByIdAndChainConfig_Id(nodeId, chainId)).thenReturn(Optional.of(existing));

        service.refreshCapabilities(chainId, nodeId);

        verify(chaincacheClient, org.mockito.Mockito.never()).probeCapabilities(any(), any());
        verify(rpcNodeRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("refreshCapabilities re-probes and saves for a CHAINCACHE node")
    void refreshCapabilities_reProbesForChaincache() {
        RpcNode existing = new RpcNode();
        existing.setChainConfig(chain());
        existing.setKind(RpcNode.NodeKind.CHAINCACHE);
        existing.setManagementUrl("http://chaincache:8080");
        existing.setRemoteChainKey("anvil");
        UUID nodeId = UUID.randomUUID();
        when(rpcNodeRepository.findByIdAndChainConfig_Id(nodeId, chainId)).thenReturn(Optional.of(existing));
        when(chaincacheClient.probeCapabilities("http://chaincache:8080", "anvil"))
                .thenReturn(Optional.of(new ChaincacheClient.ChainCapabilitiesProbe(
                        "anvil", "INSTANT", 0L, 0L, List.of("eth"), false, "UNKNOWN", true, false)));

        service.refreshCapabilities(chainId, nodeId);

        ArgumentCaptor<RpcNode> captor = ArgumentCaptor.forClass(RpcNode.class);
        verify(rpcNodeRepository).save(captor.capture());
        assertThat(captor.getValue().getCapabilities()).containsEntry("finalityModel", "INSTANT");
    }

    @Test
    @DisplayName("toResponse carries kind/managementUrl/remoteChainKey/capabilities through")
    void toResponse_carriesChaincacheFields() {
        RpcNode node = new RpcNode();
        node.setChainConfig(chain());
        node.setUrl("http://chaincache:8080/anvil/rpc");
        node.setLabel("Chaincache");
        node.setKind(RpcNode.NodeKind.CHAINCACHE);
        node.setManagementUrl("http://chaincache:8080");
        node.setRemoteChainKey("anvil");
        node.setCapabilities(java.util.Map.of("finalityModel", "INSTANT"));

        RpcNodeResponse response = service.toResponse(node);

        assertThat(response.kind()).isEqualTo(RpcNode.NodeKind.CHAINCACHE);
        assertThat(response.managementUrl()).isEqualTo("http://chaincache:8080");
        assertThat(response.remoteChainKey()).isEqualTo("anvil");
        assertThat(response.capabilities()).containsEntry("finalityModel", "INSTANT");
    }
}
