import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ChainConfig, ChainHealth, RpcNode, RpcNodeKind } from '../models';

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
  finalitySource?: string;
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
  finalitySource?: string;
}

export interface RpcNodeWriteRequest {
  url: string;
  label?: string;
  kind?: RpcNodeKind;
  managementUrl?: string;
  remoteChainKey?: string;
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

  refreshCapabilities(chainId: string, nodeId: string): Observable<RpcNode> {
    return this.http.post<RpcNode>(`${this.base}/${chainId}/nodes/${nodeId}/refresh-capabilities`, {});
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
