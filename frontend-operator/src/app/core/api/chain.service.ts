import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ChainConfig, ChainHealth, RpcNode } from '../models';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

/** `@RequiresStepUp` reason of every RPC-node change (P4C-1). */
export const RPC_NODE_CHANGE = 'RPC_NODE_CHANGE';

// No finalitySource field on either request: it is fully auto-derived backend-side from the
// chain's node set (see ChainConfig.FinalitySource's javadoc) — there is nothing for an operator
// to set here.
export interface ChainConfigCreateRequest {
  identifier: string;
  displayName: string;
  chainType: string;
  networkType: string;
  chainId?: number;
  rpcUrl: string;
  wsUrl?: string;
  blockExplorerUrl?: string;
  graphNodeUrl?: string;
  graphSubgraphName?: string;
  finalityModel?: string;
  avgBlockSeconds?: number;
}

export interface ChainConfigUpdateRequest {
  displayName?: string;
  chainId?: number;
  rpcUrl?: string;
  wsUrl?: string;
  blockExplorerUrl?: string;
  graphNodeUrl?: string;
  graphSubgraphName?: string;
  finalityModel?: string;
  avgBlockSeconds?: number;
}

// No kind/managementUrl/remoteChainKey fields: whether this node is a chaincache connection is
// auto-detected backend-side from `url` alone (see ChaincacheClient#detect) — there is nothing
// else for an operator to declare.
export interface RpcNodeWriteRequest {
  url: string;
  label?: string;
}

@Injectable({ providedIn: 'root' })
export class ChainService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/chains`;

  // ── Chain configuration ─────────────────────────────────────────────────

  listChains(): Observable<ChainConfig[]> {
    return this.http.get<ChainConfig[]>(this.base);
  }

  createChain(request: ChainConfigCreateRequest): Observable<ChainConfig> {
    return this.http.post<ChainConfig>(this.base, request);
  }

  updateChain(chainId: string, request: ChainConfigUpdateRequest): Observable<ChainConfig> {
    return this.http.patch<ChainConfig>(`${this.base}/${chainId}`, request);
  }

  enableChain(chainId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/enable`, {});
  }

  disableChain(chainId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/disable`, {});
  }

  getHealth(): Observable<ChainHealth[]> {
    return this.http.get<ChainHealth[]>(`${this.base}/health`);
  }

  // ── RPC nodes ────────────────────────────────────────────────────────────

  getNodes(chainId: string): Observable<RpcNode[]> {
    return this.http.get<RpcNode[]>(`${this.base}/${chainId}/nodes`);
  }

  /** Every node change needs step-up + a second approver (P4C-1). */
  addNode(chainId: string, request: RpcNodeWriteRequest, tokens: DualControlTokens): Observable<RpcNode> {
    return this.http.post<RpcNode>(`${this.base}/${chainId}/nodes`, request, { headers: dualControlHeaders(tokens) });
  }

  updateNode(chainId: string, nodeId: string, request: RpcNodeWriteRequest, tokens: DualControlTokens): Observable<RpcNode> {
    return this.http.put<RpcNode>(`${this.base}/${chainId}/nodes/${nodeId}`, request, { headers: dualControlHeaders(tokens) });
  }

  /** Re-runs chaincache detection for one node on demand — works in both directions (promotes a
   *  DIRECT_RPC node whose URL now answers as chaincache; falls a CHAINCACHE node back to
   *  DIRECT_RPC if chaincache no longer serves it). The periodic backend job already does this
   *  for every enabled node on its own; this is a manual "check now" trigger. */
  redetect(chainId: string, nodeId: string): Observable<RpcNode> {
    return this.http.post<RpcNode>(`${this.base}/${chainId}/nodes/${nodeId}/redetect`, {});
  }

  enableNode(chainId: string, nodeId: string, tokens: DualControlTokens): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/enable`, {}, { headers: dualControlHeaders(tokens) });
  }

  disableNode(chainId: string, nodeId: string, tokens: DualControlTokens): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/disable`, {}, { headers: dualControlHeaders(tokens) });
  }

  setExclusive(chainId: string, nodeId: string, value: boolean, tokens: DualControlTokens): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/exclusive?value=${value}`, {}, { headers: dualControlHeaders(tokens) });
  }

  deleteNode(chainId: string, nodeId: string, tokens: DualControlTokens): Observable<void> {
    return this.http.delete<void>(`${this.base}/${chainId}/nodes/${nodeId}`, { headers: dualControlHeaders(tokens) });
  }

  /** Clears the chain's pinned genesis hash; the next health round re-captures it (recovery after a
   *  legitimate devnet reset). Step-up + second approver. */
  resetGenesisPin(chainId: string, tokens: DualControlTokens): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/genesis-pin/reset`, {}, { headers: dualControlHeaders(tokens) });
  }

  /** Mints a short-lived (5 min) chaincache bearer token for pasting into chaincache's own
   *  console dialog — see RpcNodeService#mintConsoleToken. 404s for a non-chaincache node or a
   *  deployment with no registerwerk.chaincache.jwt-secret configured. */
  mintConsoleToken(chainId: string, nodeId: string): Observable<{ token: string }> {
    return this.http.post<{ token: string }>(`${this.base}/${chainId}/nodes/${nodeId}/console-token`, {});
  }
}
