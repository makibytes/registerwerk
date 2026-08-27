package de.makibytes.registerwerk.chain.internal;

import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.ChaincacheStreamStatus;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RpcNodeService — URL-only auto-detection of chaincache vs. direct-RPC nodes, "
        + "auto-derived ChainConfig.finalitySource")
class RpcNodeServiceTest {

    @Mock RpcNodeRepository rpcNodeRepository;
    @Mock ChainConfigRepository chainConfigRepository;
    @Mock ApplicationEventPublisher events;
    @Mock ChaincacheClient chaincacheClient;
    private final ChaincacheStreamStatus streamStatus = chainConfigId -> false;
    private final ChaincacheCredentials chaincacheCredentials = managementUrl -> Optional.empty();

    private RpcNodeService service;
    private final UUID chainId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RpcNodeService(rpcNodeRepository, chainConfigRepository, events, chaincacheClient,
                new ObjectMapper(), streamStatus, chaincacheCredentials);
    }

    private ChainConfig chain() {
        ChainConfig chain = new ChainConfig();
        chain.setId(chainId);
        chain.setIdentifier("ANVIL_TESTNET");
        return chain;
    }

    private static ChaincacheClient.ChainCapabilitiesProbe probe() {
        return new ChaincacheClient.ChainCapabilitiesProbe(
                "anvil", "shared-db", "INSTANT", 0L, 0L, List.of("eth", "debug"), true,
                new ChaincacheClient.AddressTraceCapability(true, true, "2026-08-21T22:00:00Z"), true, "2",
                1, 1);
    }

    @Test
    @DisplayName("addNode has no kind parameter — a plain URL detects as DIRECT_RPC")
    void addNode_directUrl_detectsAsDirectRpc() {
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(chaincacheClient.detect("http://anvil:8545")).thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(false);

        RpcNode node = service.addNode(chainId, "http://anvil:8545", "Anvil");

        assertThat(node.getKind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(node.getCapabilities()).isNull();
    }

    @Test
    @DisplayName("addNode auto-detects a chaincache URL as CHAINCACHE and stores its capabilities — "
            + "no kind/managementUrl/remoteChainKey ever supplied by the caller")
    void addNode_chaincacheUrl_autoDetectsAndStoresCapabilities() {
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc")).thenReturn(
                new ChaincacheClient.NodeDetection(RpcNode.NodeKind.CHAINCACHE,
                        "http://chaincache:8080", "anvil", probe(), false, false));
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(true);

        RpcNode node = service.addNode(chainId, "http://chaincache:8080/anvil/rpc", "Chaincache");

        assertThat(node.getKind()).isEqualTo(RpcNode.NodeKind.CHAINCACHE);
        assertThat(node.getManagementUrl()).isEqualTo("http://chaincache:8080");
        assertThat(node.getRemoteChainKey()).isEqualTo("anvil");
        assertThat(node.getCapabilities()).containsEntry("durabilityDomainId", "shared-db");
        assertThat(node.getCapabilities()).containsEntry("finalityModel", "INSTANT");
        assertThat(node.getCapabilities()).containsEntry("durableStreamAvailable", true);
    }

    @Test
    @DisplayName("addNode falls back cleanly to DIRECT_RPC when detection finds no match — no 'unknown' state")
    void addNode_detectionMiss_fallsBackToDirectRpc() {
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc"))
                .thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(false);

        RpcNode node = service.addNode(chainId, "http://chaincache:8080/anvil/rpc", "Chaincache");

        assertThat(node.getKind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(node.getCapabilities()).isNull();
    }

    @Test
    @DisplayName("addNode with an enabled CHAINCACHE-kind node auto-derives the chain's finalitySource")
    void addNode_chaincacheNode_derivesChainFinalitySource() {
        ChainConfig chain = chain();
        chain.setFinalitySource(ChainConfig.FinalitySource.RPC_SELF_PROBE);
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(chaincacheClient.detect(anyString())).thenReturn(
                new ChaincacheClient.NodeDetection(RpcNode.NodeKind.CHAINCACHE,
                        "http://chaincache:8080", "anvil", probe(), false, false));
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(true);

        service.addNode(chainId, "http://chaincache:8080/anvil/rpc", "Chaincache");

        verify(chainConfigRepository).updateFinalitySource(chainId, ChainConfig.FinalitySource.CHAINCACHE);
    }

    @Test
    @DisplayName("updateNode re-detects from the new URL — no kind parameter to pass")
    void updateNode_reDetectsFromNewUrl() {
        RpcNode existing = new RpcNode();
        existing.setChainConfig(chain());
        existing.setKind(RpcNode.NodeKind.CHAINCACHE);
        existing.setCapabilities(java.util.Map.of("finalityModel", "INSTANT"));
        UUID nodeId = UUID.randomUUID();
        when(rpcNodeRepository.findByIdAndChainConfig_Id(nodeId, chainId)).thenReturn(Optional.of(existing));
        when(chaincacheClient.detect("http://anvil:8545")).thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(false);

        RpcNode updated = service.updateNode(chainId, nodeId, "http://anvil:8545", "Anvil");

        assertThat(updated.getKind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(updated.getCapabilities()).isNull();
    }

    @Test
    @DisplayName("redetect promotes a DIRECT_RPC node whose URL now answers as chaincache")
    void redetect_promotesDirectRpcToChaincache() {
        RpcNode existing = new RpcNode();
        existing.setChainConfig(chain());
        existing.setUrl("http://chaincache:8080/anvil/rpc");
        existing.setKind(RpcNode.NodeKind.DIRECT_RPC);
        UUID nodeId = UUID.randomUUID();
        when(rpcNodeRepository.findByIdAndChainConfig_Id(nodeId, chainId)).thenReturn(Optional.of(existing));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc")).thenReturn(
                new ChaincacheClient.NodeDetection(RpcNode.NodeKind.CHAINCACHE,
                        "http://chaincache:8080", "anvil", probe(), false, false));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(true);

        RpcNode result = service.redetect(chainId, nodeId);

        assertThat(result.getKind()).isEqualTo(RpcNode.NodeKind.CHAINCACHE);
        assertThat(result.getCapabilities()).containsEntry("finalityModel", "INSTANT");
    }

    @Test
    @DisplayName("redetectAll skips disabled nodes entirely and never uses full-entity save")
    void redetectAll_skipsDisabledNodesNeverSaves() {
        RpcNode direct = new RpcNode();
        direct.setChainConfig(chain());
        direct.setUrl("http://anvil:8545");
        direct.setKind(RpcNode.NodeKind.DIRECT_RPC);
        direct.setEnabled(true);

        RpcNode disabled = new RpcNode();
        disabled.setChainConfig(chain());
        disabled.setUrl("http://also-anvil:8545");
        disabled.setEnabled(false);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(direct, disabled));
        when(chaincacheClient.detect("http://anvil:8545")).thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(false);

        service.redetectAll();

        verify(chaincacheClient, never()).detect("http://also-anvil:8545");
        verify(rpcNodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("redetectAll resets finality when the last Chaincache node is disabled")
    void redetectAll_disabledLastChaincacheNodeResetsFinalitySource() {
        ChainConfig chain = chain();
        chain.setFinalitySource(ChainConfig.FinalitySource.CHAINCACHE);
        RpcNode disabled = new RpcNode();
        disabled.setChainConfig(chain);
        disabled.setUrl("http://chaincache:8080/anvil/rpc");
        disabled.setKind(RpcNode.NodeKind.CHAINCACHE);
        disabled.setEnabled(false);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(disabled));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(
                chainId, RpcNode.NodeKind.CHAINCACHE)).thenReturn(false);

        service.redetectAll();

        verify(chaincacheClient, never()).detect(anyString());
        verify(chainConfigRepository).updateFinalitySource(
                chainId, ChainConfig.FinalitySource.RPC_SELF_PROBE);
    }

    @Test
    @DisplayName("redetectAll writes nothing for a node that is still DIRECT_RPC after redetection")
    void redetectAll_noOpWhenStillDirectRpc() {
        RpcNode direct = new RpcNode();
        direct.setChainConfig(chain());
        direct.setUrl("http://anvil:8545");
        direct.setKind(RpcNode.NodeKind.DIRECT_RPC);
        direct.setEnabled(true);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(direct));
        when(chaincacheClient.detect("http://anvil:8545")).thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(false);

        service.redetectAll();

        verify(rpcNodeRepository, never()).updateChaincacheDetection(any(), any(), any(), any(), any());
        verify(rpcNodeRepository, never()).incrementChaincacheProbeFailures(any());
        verify(rpcNodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("redetectAll promotes a DIRECT_RPC node to CHAINCACHE immediately, via the targeted update")
    void redetectAll_promotesDirectRpcToChaincache() {
        RpcNode direct = new RpcNode();
        direct.setChainConfig(chain());
        direct.setUrl("http://chaincache:8080/anvil/rpc");
        direct.setKind(RpcNode.NodeKind.DIRECT_RPC);
        direct.setEnabled(true);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(direct));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc")).thenReturn(
                new ChaincacheClient.NodeDetection(RpcNode.NodeKind.CHAINCACHE,
                        "http://chaincache:8080", "anvil", probe(), false, false));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(true);

        service.redetectAll();

        verify(rpcNodeRepository).updateChaincacheDetection(
                any(), eq("CHAINCACHE"), eq("http://chaincache:8080"), eq("anvil"), any());
        verify(rpcNodeRepository, never()).incrementChaincacheProbeFailures(any());
    }

    @Test
    @DisplayName("redetectAll demotes a CHAINCACHE node immediately when chaincache is reachable but "
            + "no longer lists its remoteChainKey — no hysteresis for a real signal")
    void redetectAll_demotesImmediatelyOnReachableButNoMatch() {
        RpcNode chaincacheNode = new RpcNode();
        chaincacheNode.setChainConfig(chain());
        chaincacheNode.setUrl("http://chaincache:8080/anvil/rpc");
        chaincacheNode.setKind(RpcNode.NodeKind.CHAINCACHE);
        chaincacheNode.setManagementUrl("http://chaincache:8080");
        chaincacheNode.setRemoteChainKey("anvil");
        chaincacheNode.setEnabled(true);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(chaincacheNode));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc"))
                .thenReturn(ChaincacheClient.NodeDetection.directRpcReachableButNoMatch());
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(false);

        service.redetectAll();

        verify(rpcNodeRepository).updateChaincacheDetection(any(), eq("DIRECT_RPC"), eq(null), eq(null), eq(null));
        verify(rpcNodeRepository, never()).incrementChaincacheProbeFailures(any());
    }

    @Test
    @DisplayName("redetectAll tolerates a transient unreachable probe on a CHAINCACHE node — increments "
            + "the failure counter instead of demoting immediately")
    void redetectAll_toleratesTransientFailureBeforeThreshold() {
        RpcNode chaincacheNode = new RpcNode();
        chaincacheNode.setChainConfig(chain());
        chaincacheNode.setUrl("http://chaincache:8080/anvil/rpc");
        chaincacheNode.setKind(RpcNode.NodeKind.CHAINCACHE);
        chaincacheNode.setManagementUrl("http://chaincache:8080");
        chaincacheNode.setRemoteChainKey("anvil");
        chaincacheNode.setEnabled(true);
        chaincacheNode.setChaincacheProbeFailures(1);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(chaincacheNode));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc"))
                .thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(true);

        service.redetectAll();

        verify(rpcNodeRepository, never()).updateChaincacheDetection(any(), any(), any(), any(), any());
        verify(rpcNodeRepository).incrementChaincacheProbeFailures(chaincacheNode.getId());
    }

    @Test
    @DisplayName("redetectAll demotes a CHAINCACHE node after the third consecutive unreachable probe")
    void redetectAll_demotesAfterThresholdConsecutiveFailures() {
        RpcNode chaincacheNode = new RpcNode();
        chaincacheNode.setChainConfig(chain());
        chaincacheNode.setUrl("http://chaincache:8080/anvil/rpc");
        chaincacheNode.setKind(RpcNode.NodeKind.CHAINCACHE);
        chaincacheNode.setManagementUrl("http://chaincache:8080");
        chaincacheNode.setRemoteChainKey("anvil");
        chaincacheNode.setEnabled(true);
        chaincacheNode.setChaincacheProbeFailures(2);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(chaincacheNode));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc"))
                .thenReturn(ChaincacheClient.NodeDetection.directRpc());
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(false);

        service.redetectAll();

        verify(rpcNodeRepository).updateChaincacheDetection(any(), eq("DIRECT_RPC"), eq(null), eq(null), eq(null));
        verify(rpcNodeRepository, never()).incrementChaincacheProbeFailures(any());
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

    @Test
    @DisplayName("redetectAll: a 401/403 leaves a confirmed CHAINCACHE node completely untouched — "
            + "no demotion, no failure-counter increment, just a bad credential to fix")
    void redetectAll_unauthorized_skipsEntirely() {
        RpcNode chaincacheNode = new RpcNode();
        chaincacheNode.setChainConfig(chain());
        chaincacheNode.setUrl("http://chaincache:8080/anvil/rpc");
        chaincacheNode.setKind(RpcNode.NodeKind.CHAINCACHE);
        chaincacheNode.setManagementUrl("http://chaincache:8080");
        chaincacheNode.setRemoteChainKey("anvil");
        chaincacheNode.setEnabled(true);
        chaincacheNode.setChaincacheProbeFailures(2);

        when(rpcNodeRepository.findAllWithChainConfig()).thenReturn(List.of(chaincacheNode));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc"))
                .thenReturn(ChaincacheClient.NodeDetection.directRpcUnauthorized());
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(true);

        service.redetectAll();

        verify(rpcNodeRepository, never()).updateChaincacheDetection(any(), any(), any(), any(), any());
        verify(rpcNodeRepository, never()).incrementChaincacheProbeFailures(any());
        verify(rpcNodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("addNode: an unauthorized probe adds the node as plain DIRECT_RPC — a bad/missing "
            + "credential is not evidence the node isn't chaincache")
    void addNode_unauthorized_addsAsDirectRpc() {
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain()));
        when(chaincacheClient.detect("http://chaincache:8080/anvil/rpc"))
                .thenReturn(ChaincacheClient.NodeDetection.directRpcUnauthorized());
        when(rpcNodeRepository.save(any(RpcNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rpcNodeRepository.existsByChainConfig_IdAndKindAndEnabledTrue(chainId, RpcNode.NodeKind.CHAINCACHE))
                .thenReturn(false);

        RpcNode node = service.addNode(chainId, "http://chaincache:8080/anvil/rpc", "Chaincache");

        assertThat(node.getKind()).isEqualTo(RpcNode.NodeKind.DIRECT_RPC);
        assertThat(node.getManagementUrl()).isNull();
        assertThat(node.getRemoteChainKey()).isNull();
        assertThat(node.getCapabilities()).isNull();
    }
}
