package de.makibytes.registerwerk.chain.web;

import de.makibytes.registerwerk.chain.internal.RpcNodeService;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.chain.api.RpcNodeActor;
import de.makibytes.registerwerk.shared.SecurityUtils;
import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import de.makibytes.registerwerk.stepup.api.StepUpAttributes;
import de.makibytes.registerwerk.chain.web.dto.ConsoleTokenResponse;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeCreateRequest;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeResponse;
import de.makibytes.registerwerk.chain.web.dto.RpcNodeUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Manages RPC nodes for each chain configuration.
 * All endpoints require the {@code REGISTRY_ADMIN} role. Every state change (add, update, enable,
 * disable, exclusive, delete, genesis-pin reset) additionally requires step-up authentication AND a
 * second approver (P4C-1): routing traffic to a node decides which chain state the registry believes.
 * The approver and the old/new URL are recorded in the audit event.
 */
@RestController
@RequestMapping("/api/v1/admin/chains/{chainId}/nodes")
@PreAuthorize("hasRole('REGISTRY_ADMIN')")
public class RpcNodeController {

    private static final String STEP_UP_REASON = "RPC_NODE_CHANGE";

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
    @RequiresStepUp(requireSecondApprover = true, reason = STEP_UP_REASON)
    public ResponseEntity<RpcNodeResponse> addNode(
            @PathVariable UUID chainId,
            @RequestBody @Valid RpcNodeCreateRequest request,
            Authentication auth, HttpServletRequest httpRequest) {
        RpcNode node = rpcNodeService.addNode(chainId, request.url(), request.label(), actor(auth, httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(rpcNodeService.toResponse(node));
    }

    /** Updates an existing node's URL/label — re-detected on every update, same as add. */
    @PutMapping("/{nodeId}")
    @RequiresStepUp(requireSecondApprover = true, reason = STEP_UP_REASON)
    public ResponseEntity<RpcNodeResponse> updateNode(
            @PathVariable UUID chainId,
            @PathVariable UUID nodeId,
            @RequestBody @Valid RpcNodeUpdateRequest request,
            Authentication auth, HttpServletRequest httpRequest) {
        RpcNode node = rpcNodeService.updateNode(chainId, nodeId, request.url(), request.label(),
                actor(auth, httpRequest));
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
    @RequiresStepUp(requireSecondApprover = true, reason = STEP_UP_REASON)
    public ResponseEntity<Void> enable(@PathVariable UUID chainId, @PathVariable UUID nodeId, Authentication auth, HttpServletRequest httpRequest) {
        rpcNodeService.enable(chainId, nodeId, actor(auth, httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** Manually stops (disables) a node. Traffic is routed away from it. */
    @PostMapping("/{nodeId}/disable")
    @RequiresStepUp(requireSecondApprover = true, reason = STEP_UP_REASON)
    public ResponseEntity<Void> disable(@PathVariable UUID chainId, @PathVariable UUID nodeId, Authentication auth, HttpServletRequest httpRequest) {
        rpcNodeService.disable(chainId, nodeId, actor(auth, httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** Sets the exclusive flag on a node (true = pin traffic to this node). */
    @PostMapping("/{nodeId}/exclusive")
    @RequiresStepUp(requireSecondApprover = true, reason = STEP_UP_REASON)
    public ResponseEntity<Void> setExclusive(
            @PathVariable UUID chainId,
            @PathVariable UUID nodeId,
            @RequestParam boolean value,
            Authentication auth, HttpServletRequest httpRequest) {
        rpcNodeService.setExclusive(chainId, nodeId, value, actor(auth, httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** Removes a node permanently. */
    @DeleteMapping("/{nodeId}")
    @RequiresStepUp(requireSecondApprover = true, reason = STEP_UP_REASON)
    public ResponseEntity<Void> delete(@PathVariable UUID chainId, @PathVariable UUID nodeId, Authentication auth, HttpServletRequest httpRequest) {
        rpcNodeService.delete(chainId, nodeId, actor(auth, httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** Clears the chain's pinned genesis hash; the next health round re-captures it from a node whose
     *  chain id matches the pin. Recovery path after a legitimate devnet reset. */
    @PostMapping("/genesis-pin/reset")
    @RequiresStepUp(requireSecondApprover = true, reason = STEP_UP_REASON)
    public ResponseEntity<Void> resetGenesisPin(@PathVariable UUID chainId, Authentication auth, HttpServletRequest httpRequest) {
        rpcNodeService.resetGenesisPin(chainId, actor(auth, httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** The request attributes are read inside the method body: the step-up aspect populates the
     *  request id only after argument resolution. */
    private static RpcNodeActor actor(Authentication auth, HttpServletRequest request) {
        UUID approverId = request.getAttribute(StepUpAttributes.DUAL_CONTROL_APPROVER_ID) instanceof UUID id ? id : null;
        UUID requestId = request.getAttribute(StepUpAttributes.DUAL_CONTROL_REQUEST_ID) instanceof UUID id ? id : null;
        return new RpcNodeActor(SecurityUtils.extractUserId(auth),
                SecurityUtils.primaryRole(auth, "REGISTRY_ADMIN"), approverId, requestId);
    }
}
