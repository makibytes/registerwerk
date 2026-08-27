package de.makibytes.registerwerk.chain.web;

import de.makibytes.registerwerk.chain.internal.RpcNodeService;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.web.dto.ConsoleTokenResponse;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeCreateRequest;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeResponse;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeUpdateRequest;
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

    /** Adds a new RPC node to the given chain. Whether this becomes a chaincache connection is
     *  auto-detected from {@code url} alone — there is no {@code kind} field to set; see
     *  {@code RpcNodeService#addNode}. */
    @PostMapping
    public ResponseEntity<RpcNodeResponse> addNode(
            @PathVariable UUID chainId,
            @RequestBody @Valid RpcNodeCreateRequest request) {
        RpcNode node = rpcNodeService.addNode(chainId, request.url(), request.label());
        return ResponseEntity.status(HttpStatus.CREATED).body(rpcNodeService.toResponse(node));
    }

    /** Updates an existing node's URL/label — re-detected on every update, same as add. */
    @PutMapping("/{nodeId}")
    public ResponseEntity<RpcNodeResponse> updateNode(
            @PathVariable UUID chainId,
            @PathVariable UUID nodeId,
            @RequestBody @Valid RpcNodeUpdateRequest request) {
        RpcNode node = rpcNodeService.updateNode(chainId, nodeId, request.url(), request.label());
        return ResponseEntity.ok(rpcNodeService.toResponse(node));
    }

    /** Re-runs chaincache detection for one node on demand, in both directions (promotes a
     *  {@code DIRECT_RPC} node whose URL now answers as chaincache; falls a {@code CHAINCACHE}
     *  node back to {@code DIRECT_RPC} if chaincache no longer serves it) — a manual trigger for
     *  the periodic background job {@code RpcNodeService#redetectAll} already runs on every
     *  enabled node. */
    @PostMapping("/{nodeId}/redetect")
    public ResponseEntity<RpcNodeResponse> redetect(
            @PathVariable UUID chainId, @PathVariable UUID nodeId) {
        RpcNode node = rpcNodeService.redetect(chainId, nodeId);
        return ResponseEntity.ok(rpcNodeService.toResponse(node));
    }

    /** Mints a short-lived (5 min) chaincache bearer token for the given node, for the operator to
     *  paste into chaincache's own console dialog — see {@code RpcNodeService#mintConsoleToken}.
     *  404s if the node isn't a chaincache connection or this deployment has no
     *  {@code registerwerk.chaincache.jwt-secret} configured. */
    @PostMapping("/{nodeId}/console-token")
    public ResponseEntity<ConsoleTokenResponse> mintConsoleToken(
            @PathVariable UUID chainId, @PathVariable UUID nodeId) {
        return rpcNodeService.mintConsoleToken(chainId, nodeId)
                .map(token -> ResponseEntity.ok(new ConsoleTokenResponse(token)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Manually enables (un-stops) a node. */
    @PostMapping("/{nodeId}/enable")
    public ResponseEntity<Void> enable(@PathVariable UUID chainId, @PathVariable UUID nodeId) {
        rpcNodeService.enable(chainId, nodeId);
        return ResponseEntity.noContent().build();
    }

    /** Manually stops (disables) a node. Traffic is routed away from it. */
    @PostMapping("/{nodeId}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID chainId, @PathVariable UUID nodeId) {
        rpcNodeService.disable(chainId, nodeId);
        return ResponseEntity.noContent().build();
    }

    /** Sets the exclusive flag on a node (true = pin traffic to this node). */
    @PostMapping("/{nodeId}/exclusive")
    public ResponseEntity<Void> setExclusive(
            @PathVariable UUID chainId,
            @PathVariable UUID nodeId,
            @RequestParam boolean value) {
        rpcNodeService.setExclusive(chainId, nodeId, value);
        return ResponseEntity.noContent().build();
    }

    /** Removes a node permanently. */
    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Void> delete(@PathVariable UUID chainId, @PathVariable UUID nodeId) {
        rpcNodeService.delete(chainId, nodeId);
        return ResponseEntity.noContent().build();
    }
}
