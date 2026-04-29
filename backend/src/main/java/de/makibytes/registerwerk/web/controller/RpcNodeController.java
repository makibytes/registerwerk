package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.chain.RpcNodeService;
import de.makibytes.registerwerk.domain.chain.RpcNode;
import de.makibytes.registerwerk.web.dto.RpcNodeCreateRequest;
import de.makibytes.registerwerk.web.dto.RpcNodeResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Manages RPC nodes for each chain configuration.
 * All endpoints require the {@code REGISTRY_ADMIN} role.
 */
@RestController
@RequestMapping("/api/v1/admin/chains/{chainId}/nodes")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class RpcNodeController {

    private final RpcNodeService rpcNodeService;

    public RpcNodeController(RpcNodeService rpcNodeService) {
        this.rpcNodeService = rpcNodeService;
    }

    /** Returns all RPC nodes for the given chain. */
    @GetMapping
    public ResponseEntity<List<RpcNodeResponse>> listNodes(@PathVariable UUID chainId) {
        List<RpcNodeResponse> nodes = rpcNodeService.listByChain(chainId).stream()
                .map(rpcNodeService::toResponse)
                .toList();
        return ResponseEntity.ok(nodes);
    }

    /** Adds a new RPC node to the given chain. */
    @PostMapping
    public ResponseEntity<RpcNodeResponse> addNode(
            @PathVariable UUID chainId,
            @RequestBody @Valid RpcNodeCreateRequest request) {
        RpcNode node = rpcNodeService.addNode(chainId, request.url(), request.label());
        return ResponseEntity.status(HttpStatus.CREATED).body(rpcNodeService.toResponse(node));
    }

    /** Manually enables (un-stops) a node. */
    @PostMapping("/{nodeId}/enable")
    public ResponseEntity<Void> enable(@PathVariable UUID chainId, @PathVariable UUID nodeId) {
        rpcNodeService.enable(nodeId);
        return ResponseEntity.noContent().build();
    }

    /** Manually stops (disables) a node. Traffic is routed away from it. */
    @PostMapping("/{nodeId}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID chainId, @PathVariable UUID nodeId) {
        rpcNodeService.disable(nodeId);
        return ResponseEntity.noContent().build();
    }

    /** Sets the exclusive flag on a node (true = pin traffic to this node). */
    @PostMapping("/{nodeId}/exclusive")
    public ResponseEntity<Void> setExclusive(
            @PathVariable UUID chainId,
            @PathVariable UUID nodeId,
            @RequestParam boolean value) {
        rpcNodeService.setExclusive(nodeId, value);
        return ResponseEntity.noContent().build();
    }

    /** Removes a node permanently. */
    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Void> delete(@PathVariable UUID chainId, @PathVariable UUID nodeId) {
        rpcNodeService.delete(nodeId);
        return ResponseEntity.noContent().build();
    }
}
