package de.makibytes.registerwerk.chain.web;

import de.makibytes.registerwerk.chain.api.ChainConfigService;
import de.makibytes.registerwerk.chain.internal.RpcNodeService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.web.dto.ChainConfigCreateRequest;
import de.makibytes.registerwerk.chain.web.dto.ChainConfigResponse;
import de.makibytes.registerwerk.chain.web.dto.ChainHealthResponse;
import de.makibytes.registerwerk.chain.web.dto.ChainConfigUpdateRequest;
import de.makibytes.registerwerk.chain.web.ChainConfigMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing dynamic chain configurations.
 * All endpoints require the {@code REGISTRY_ADMIN} role.
 */
@RestController
@RequestMapping("/api/v1/admin/chains")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class ChainConfigController {

    private static final Logger log = LoggerFactory.getLogger(ChainConfigController.class);

    private final ChainConfigService chainConfigService;
    private final ChainConfigMapper chainConfigMapper;
    private final RpcNodeService rpcNodeService;

    public ChainConfigController(
            ChainConfigService chainConfigService,
            ChainConfigMapper chainConfigMapper,
            RpcNodeService rpcNodeService) {
        this.chainConfigService = chainConfigService;
        this.chainConfigMapper  = chainConfigMapper;
        this.rpcNodeService     = rpcNodeService;
    }

    /** Returns all chain configurations, including disabled ones. */
    @GetMapping
    public ResponseEntity<List<ChainConfigResponse>> listChains() {
        List<ChainConfigResponse> response = chainConfigService.listAll()
                .stream()
                .map(chainConfigMapper::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    /** Creates a new chain configuration. Returns 201 with the created resource. */
    @PostMapping
    public ResponseEntity<ChainConfigResponse> createChain(
            @RequestBody @Valid ChainConfigCreateRequest request) {
        ChainConfig entity = chainConfigMapper.toEntity(request);
        ChainConfig created = chainConfigService.create(entity);
        log.info("Created chain config: identifier={}", created.getIdentifier());
        return ResponseEntity.status(HttpStatus.CREATED).body(chainConfigMapper.toResponse(created));
    }

    /** Returns a single chain configuration by UUID. */
    @GetMapping("/{id}")
    public ResponseEntity<ChainConfigResponse> getChain(@PathVariable UUID id) {
        return ResponseEntity.ok(chainConfigMapper.toResponse(chainConfigService.getById(id)));
    }

    /**
     * Partially updates a chain configuration. Only non-null fields in the request body
     * are applied.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ChainConfigResponse> updateChain(
            @PathVariable UUID id,
            @RequestBody @Valid ChainConfigUpdateRequest request) {
        // Map the incoming request fields onto a patch entity; null-valued fields will be ignored.
        ChainConfig patch = new ChainConfig();
        patch.setDisplayName(request.displayName());
        patch.setRpcUrl(request.rpcUrl());
        patch.setWsUrl(request.wsUrl());
        patch.setBlockExplorerUrl(request.blockExplorerUrl());
        patch.setGraphNodeUrl(request.graphNodeUrl());
        patch.setGraphSubgraphName(request.graphSubgraphName());
        patch.setChainId(request.chainId());

        ChainConfig updated = chainConfigService.update(id, patch);
        return ResponseEntity.ok(chainConfigMapper.toResponse(updated));
    }

    /** Enables a previously disabled chain configuration. Returns 204 No Content. */
    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enableChain(@PathVariable UUID id) {
        chainConfigService.enable(id);
        return ResponseEntity.noContent().build();
    }

    /** Disables a chain configuration. Returns 204 No Content. */
    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disableChain(@PathVariable UUID id) {
        chainConfigService.disable(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Triggers a live refresh of the {@link de.makibytes.registerwerk.application.blockchain.BlockchainClientRegistry},
     * re-initialising RPC clients from the current enabled chain_config rows.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refreshClients() {
        chainConfigService.refreshBlockchainClients();
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns all chains together with their RPC nodes and current health state.
     * Used by the operator frontend health dashboard.
     */
    @GetMapping("/health")
    public ResponseEntity<List<ChainHealthResponse>> getHealth() {
        List<ChainHealthResponse> result = chainConfigService.listAll().stream()
                .map(chain -> new ChainHealthResponse(
                        chain.getId(),
                        chain.getIdentifier(),
                        chain.getDisplayName(),
                        chain.getChainType().name(),
                        chain.getNetworkType().name(),
                        chain.getChainId(),
                        chain.isEnabled(),
                        rpcNodeService.listByChain(chain.getId()).stream()
                                .map(rpcNodeService::toResponse)
                                .toList()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }
}
