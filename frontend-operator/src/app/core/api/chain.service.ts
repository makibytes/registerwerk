import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ChainConfig, ChainHealth, RpcNode } from '../models';

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

  addNode(chainId: string, request: RpcNodeWriteRequest): Observable<RpcNode> {
    return this.http.post<RpcNode>(`${this.base}/${chainId}/nodes`, request);
  }

  updateNode(chainId: string, nodeId: string, request: RpcNodeWriteRequest): Observable<RpcNode> {
    return this.http.put<RpcNode>(`${this.base}/${chainId}/nodes/${nodeId}`, request);
  }

  /** Re-runs chaincache detection for one node on demand — works in both directions (promotes a
   *  DIRECT_RPC node whose URL now answers as chaincache; falls a CHAINCACHE node back to
   *  DIRECT_RPC if chaincache no longer serves it). The periodic backend job already does this
   *  for every enabled node on its own; this is a manual "check now" trigger. */
  redetect(chainId: string, nodeId: string): Observable<RpcNode> {
    return this.http.post<RpcNode>(`${this.base}/${chainId}/nodes/${nodeId}/redetect`, {});
  }

  enableNode(chainId: string, nodeId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/enable`, {});
  }

  disableNode(chainId: string, nodeId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/disable`, {});
  }

  setExclusive(chainId: string, nodeId: string, value: boolean): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/exclusive?value=${value}`, {});
  }

  deleteNode(chainId: string, nodeId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${chainId}/nodes/${nodeId}`);
  }
}
